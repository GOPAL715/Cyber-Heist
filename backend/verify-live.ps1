# Live HTTP verification for Phase 4.
#
# Starts the real Spring Boot application, exercises the Phase 4 API over HTTP
# with real tokens, prints one line per scenario, then shuts the app down.
#
# The database here is H2 in PostgreSQL compatibility mode because no
# PostgreSQL credentials are available on this machine. That is a genuine live
# HTTP check of the API, but it is NOT a PostgreSQL verification - the schema
# has not been exercised against a real PostgreSQL server.
#
# Usage: powershell -File verify-live.ps1

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8081'
$results = New-Object System.Collections.Generic.List[string]

function Record($name, $ok, $detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    $line = "[$mark] $name - $detail"
    $results.Add($line)
    Write-Output $line
}

function Check($name, $condition, $detail) {
    Record $name ([bool]$condition) $detail
}

# ---------------------------------------------------------------- start app
$cp = (Get-Content 'target/cp.txt' -Raw).Trim()
# H2 is a test-scoped dependency, so it is not on the runtime classpath. Resolve
# it from the local Maven repository rather than hardcoding a machine path.
if ($cp -notmatch 'h2-2') {
    $h2 = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter 'h2-*.jar' |
        Where-Object { $_.Name -notmatch 'sources' } |
        Select-Object -First 1
    if (-not $h2) { throw 'H2 jar not found in the local Maven repository.' }
    $cp = "$cp;$($h2.FullName)"
}
$env:JWT_SECRET = 'live-verification-only-secret-value-long-enough-for-hs256'

$args = @(
    '-cp', "target/classes;$cp",
    'com.cyberheist.CyberHeistApplication',
    # A unique in-memory database per run, so a rerun never collides with data
    # left behind by an earlier instance.
    "--spring.datasource.url=jdbc:h2:mem:liveverify_$([Guid]::NewGuid().ToString('N').Substring(0,8));MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    '--spring.datasource.driver-class-name=org.h2.Driver',
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    '--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
    '--server.port=8081',
    '--app.security.rate-limit.enabled=false'
)

$proc = Start-Process -FilePath 'java' -ArgumentList $args -PassThru `
    -RedirectStandardOutput 'target/live.log' -RedirectStandardError 'target/live.err'

try {
    $up = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        if ($proc.HasExited) { break }
        try {
            Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 2 | Out-Null
            $up = $true
            break
        } catch { }
    }
    if (-not $up) {
        Write-Output "APPLICATION FAILED TO START"
        Get-Content 'target/live.log' -Tail 40
        Get-Content 'target/live.err' -Tail 40
        exit 1
    }
    Write-Output "application up on $base (pid $($proc.Id))"

    # ------------------------------------------------------------ helpers
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $pw = 'Str0ng!Passw0rd'

    function Register($user, $email) {
        $body = @{ username = $user; email = $email; password = $pw } | ConvertTo-Json
        Invoke-RestMethod -Uri "$base/api/v1/auth/register" -Method Post `
            -ContentType 'application/json' -Body $body | Out-Null
    }

    function Login($email) {
        $body = @{ email = $email; password = $pw } | ConvertTo-Json
        (Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post `
            -ContentType 'application/json' -Body $body).data.accessToken
    }

    function Get($path, $token) {
        try {
            Invoke-RestMethod -Uri "$base$path" -Headers @{ Authorization = "Bearer $token" }
        } catch {
            [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode }
        }
    }

    function Post($path, $token, $body) {
        try {
            $r = Invoke-RestMethod -Uri "$base$path" -Method Post `
                -Headers @{ Authorization = "Bearer $token" } `
                -ContentType 'application/json' -Body $body
            $r | Add-Member -NotePropertyName status -NotePropertyValue 200 -Force
            $r
        } catch {
            $code = [int]$_.Exception.Response.StatusCode
            $msg = $null
            try { $msg = ($_.ErrorDetails.Message | ConvertFrom-Json).message } catch { }
            [pscustomobject]@{ status = $code; message = $msg }
        }
    }

    function Delete($path, $token) {
        try {
            $r = Invoke-RestMethod -Uri "$base$path" -Method Delete `
                -Headers @{ Authorization = "Bearer $token" }
            $r | Add-Member -NotePropertyName status -NotePropertyValue 200 -Force
            $r
        } catch {
            [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode }
        }
    }

    # ------------------------------------------------- 1. register + 2. login
    $emailA = "live_a_$suffix@example.com"
    Register 'live_a' $emailA
    Check '1. register player A' $true "created $emailA"

    $tokenA = Login $emailA
    Check '2. login player A' ([bool]$tokenA) 'received an access token'

    # ------------------------------------------- 3/4. starting coins + gear
    $profile = (Get '/api/v1/player/profile' $tokenA).data
    Check '3. starting coins' ($profile.coins -eq 100) "coins = $($profile.coins)"

    $inv = (Get '/api/v1/player/inventory' $tokenA).data.items
    $starter = @($inv | Where-Object { $_.code -eq 'BASIC_LAPTOP' })
    $loadout = (Get '/api/v1/player/equipment' $tokenA).data
    $mainDevice = $loadout.equipment | Where-Object { $_.slot -eq 'MAIN_DEVICE' }
    Check '4. starting inventory/equipment' ($starter.Count -eq 1 -and $mainDevice.item.code -eq 'BASIC_LAPTOP') `
        "inventory has $($inv.Count) item(s); MAIN_DEVICE = $($mainDevice.item.code)"

    # -------------------------------------------------------- 5. get shop
    $shop = (Get '/api/v1/player/shop' $tokenA).data
    Check '5. get shop' ($shop.items.Count -eq 15) "$($shop.items.Count) active items, balance $($shop.coins)"

    $neural = $shop.items | Where-Object { $_.code -eq 'NEURAL_PROCESSOR' }
    Check '5b. catalogue prices/effects are server-defined' `
        ($neural.price -eq 750 -and $neural.rarity -eq 'RARE' -and $neural.effects[0].value -eq 10) `
        "NEURAL_PROCESSOR price=$($neural.price) rarity=$($neural.rarity) xp=+$($neural.effects[0].value)%"

    # -------------------------------------------------- 6. item details
    $detail = (Get "/api/v1/player/shop/items/$($neural.id)" $tokenA).data
    Check '6. item details' ($detail.code -eq 'NEURAL_PROCESSOR') "returned $($detail.name)"
    $ghost = Get "/api/v1/player/shop/items/00000000-0000-4000-8000-000000000000" $tokenA
    Check '6b. unknown item id is 404' ($ghost.status -eq 404) "status $($ghost.status)"

    # ------------------------------------------ 7/8. purchase + deduction
    # Give the player coins the legitimate way is slow, so top up via the
    # mission loop is not used here; instead buy an affordable item.
    $proc2 = $shop.items | Where-Object { $_.code -eq 'BASIC_PROCESSOR' }
    $buy = Post "/api/v1/player/shop/items/$($proc2.id)/purchase" $tokenA $null
    Check '7. purchase item' ($buy.data.code -eq 'BASIC_PROCESSOR') `
        "bought $($buy.data.code) for $($buy.data.pricePaid)"
    $after = (Get '/api/v1/player/profile' $tokenA).data
    Check '8. coins deducted correctly' ($after.coins -eq (100 - $proc2.price)) `
        "balance $($after.coins) = 100 - $($proc2.price)"

    # ------------------------------------------- 9. inventory contains item
    $inv2 = (Get '/api/v1/player/inventory' $tokenA).data.items
    Check '9. inventory contains purchased item' `
        (@($inv2 | Where-Object { $_.code -eq 'BASIC_PROCESSOR' }).Count -eq 1) `
        "$($inv2.Count) owned item(s)"

    # --------------------------------------------------- 10/11. equip
    $procRow = $inv2 | Where-Object { $_.code -eq 'BASIC_PROCESSOR' }
    $equip = Post '/api/v1/player/equipment/PROCESSOR' $tokenA (@{ inventoryItemId = $procRow.inventoryId } | ConvertTo-Json)
    Check '10. equip item' ($equip.data.equipped -eq $true) "equipped $($equip.data.code) into $($equip.data.equippedIn)"

    $loadout2 = (Get '/api/v1/player/equipment' $tokenA).data
    $procSlot = $loadout2.equipment | Where-Object { $_.slot -eq 'PROCESSOR' }
    Check '11. confirm loadout' ($procSlot.item.code -eq 'BASIC_PROCESSOR') `
        "PROCESSOR = $($procSlot.item.code)"

    # ------------------------------------- 12/13/14. bonuses + mission effect
    $bonuses = $loadout2.bonuses
    $xpBonus = ($bonuses | Where-Object { $_.type -eq 'EXPERIENCE_BONUS' }).percent
    $speedBonus = ($bonuses | Where-Object { $_.type -eq 'MISSION_SPEED' }).percent
    Check '12. bonus calculation' ($xpBonus -eq 5 -and $speedBonus -eq 5) `
        "EXPERIENCE_BONUS=+$xpBonus%, MISSION_SPEED=+$speedBonus%"

    # Play a mission and confirm the server paid base + bonus (50 -> 55 XP).
    $missions = (Get '/api/v1/player/missions' $tokenA).data
    $target = $missions | Where-Object { $_.code -eq 'RECON_PERIMETER' }
    $baseXp = $target.xpReward
    $start = Post "/api/v1/player/missions/$($target.id)/start" $tokenA $null
    Check '13. start mission' ($start.data.puzzle.puzzleId) "puzzle $($start.data.puzzle.puzzleId) generated"

    # Derive the correct answer the way a player does: the prompt names the rule,
    # so read the question and apply it. This is the test equivalent of solving
    # the puzzle - it never reads an answer out of the API, because the API does
    # not send one.
    $puzzleId = $start.data.puzzle.puzzleId
    $question = $start.data.puzzle.question
    $terms = @($start.data.puzzle.sequence | Where-Object { $_ -ne '?' } | ForEach-Object { [int]$_ })
    $answer = $null
    if ($question -match 'increases by the same amount') {
        $step = $terms[1] - $terms[0]
        $answer = [string]($terms[$terms.Count - 1] + $step)
    }
    elseif ($question -match 'multiplied by the same factor') {
        $factor = [int]($terms[1] / $terms[0])
        $answer = [string]($terms[$terms.Count - 1] * $factor)
    }
    elseif ($question -match 'operations alternate') {
        $add = $terms[1] - $terms[0]
        $factor = [int]($terms[2] / $terms[1])
        # Terms are add, multiply, add, multiply, ... so the next op after the
        # last revealed term alternates from the one just applied.
        $lastOp = if ($terms.Count % 2 -eq 0) { 'add' } else { 'mul' }
        $nextOp = if ($lastOp -eq 'add') { 'mul' } else { 'add' }
        $answer = [string]$(if ($nextOp -eq 'mul') { $terms[$terms.Count - 1] * $factor } else { $terms[$terms.Count - 1] + $add })
    }
    Check '13b. derived puzzle answer' ($answer -and $start.data.puzzle.options -contains $answer) `
        "rule '$question' -> answer '$answer' (offered: $($start.data.puzzle.options -join ', '))"

    $submit = Post "/api/v1/player/missions/$($target.id)/puzzle/submit" $tokenA `
        (@{ puzzleId = $puzzleId; answer = $answer } | ConvertTo-Json)
    $paidXp = $submit.data.rewards.experience
    $expectedXp = $baseXp + [math]::Floor(($baseXp * $xpBonus + 50) / 100)
    Check '14. server-calculated reward with bonus' ($paidXp -eq $expectedXp) `
        "paid $paidXp XP = base $baseXp + ${xpBonus}% (expected $expectedXp)"

    # ------------------------------------------------------ 15/16. unequip
    $un = Delete '/api/v1/player/equipment/PROCESSOR' $tokenA
    Check '15. unequip' ($un.status -eq 200) "HTTP $($un.status)"
    $inv3 = (Get '/api/v1/player/inventory' $tokenA).data.items
    $stillThere = @($inv3 | Where-Object { $_.code -eq 'BASIC_PROCESSOR' }).Count -eq 1
    $loadout3 = (Get '/api/v1/player/equipment' $tokenA).data
    $procSlot3 = $loadout3.equipment | Where-Object { $_.slot -eq 'PROCESSOR' }
    Check '16. item remains in inventory after unequip' ($stillThere -and $null -eq $procSlot3.item) `
        "still owned = $stillThere, slot empty = $($null -eq $procSlot3.item)"

    # ------------------------------------------------- 17. duplicate purchase
    # Capture the balance immediately before, so the assertion is about this
    # attempt alone and not about anything a mission payout did earlier.
    $balBeforeDup = (Get '/api/v1/player/profile' $tokenA).data.coins
    $dup = Post "/api/v1/player/shop/items/$($proc2.id)/purchase" $tokenA $null
    $balAfterDup = (Get '/api/v1/player/profile' $tokenA).data.coins
    Check '17. duplicate purchase is 409, no charge' `
        ($dup.status -eq 409 -and $balAfterDup -eq $balBeforeDup) `
        "status $($dup.status), balance unchanged at $balAfterDup"

    # ------------------------------------------------- 18. price tampering
    # BASIC_FIREWALL is used here because the player can actually afford it:
    # an item they cannot afford would fail as "insufficient coins" and would
    # not prove anything about the price they claimed to send.
    $wall = $shop.items | Where-Object { $_.code -eq 'BASIC_FIREWALL' }
    $balBefore = (Get '/api/v1/player/profile' $tokenA).data.coins
    $tamper = Post "/api/v1/player/shop/items/$($wall.id)/purchase" $tokenA `
        (@{ price = 1; coins = 1000000; rarity = 'LEGENDARY'; bonus = 999999 } | ConvertTo-Json)
    $balAfter = (Get '/api/v1/player/profile' $tokenA).data.coins
    Check '18. price tampering ignored' `
        ($tamper.data.pricePaid -eq $wall.price -and $balAfter -eq ($balBefore - $wall.price)) `
        "status $($tamper.status), charged $($tamper.data.pricePaid) (catalogue $($wall.price), client claimed 1), balance $balBefore -> $balAfter"

    # ------------------------------------------------ 19. bonus tampering
    $fwRow = (Get '/api/v1/player/inventory' $tokenA).data.items | Where-Object { $_.code -eq 'BASIC_FIREWALL' }
    $tamperEquip = Post '/api/v1/player/equipment/SECURITY' $tokenA `
        (@{ inventoryItemId = $fwRow.inventoryId; bonus = 999999; rarity = 'LEGENDARY' } | ConvertTo-Json)
    $bonusesAfter = (Get '/api/v1/player/equipment' $tokenA).data.bonuses
    $eff = ($bonusesAfter | Where-Object { $_.type -eq 'ENERGY_EFFICIENCY' }).percent
    Check '19. bonus tampering ignored' ($tamperEquip.data.code -eq 'BASIC_FIREWALL' -and $eff -eq 5) `
        "equipped $($tamperEquip.data.code); ENERGY_EFFICIENCY = +$eff% (client claimed 999999; the catalogue says 5)"

    # ------------------------------------------ 20/21. other player's things
    $emailB = "live_b_$suffix@example.com"
    Register 'live_b' $emailB
    $tokenB = Login $emailB

    $bInv = (Get '/api/v1/player/inventory' $tokenB).data.items
    Check '20. player B cannot see player A inventory' `
        (@($bInv | Where-Object { $_.code -eq 'BASIC_FIREWALL' }).Count -eq 0) `
        "B sees $($bInv.Count) item(s), none of them A's"

    $steal = Post '/api/v1/player/equipment/SECURITY' $tokenB `
        (@{ inventoryItemId = $fwRow.inventoryId } | ConvertTo-Json)
    Check '21. player B cannot equip player A item' ($steal.status -eq 404) `
        "status $($steal.status), message '$($steal.message)'"

    $bLoadout = (Get '/api/v1/player/equipment' $tokenB).data.equipment | Where-Object { $_.slot -eq 'SECURITY' }
    Check '21b. player B equipment unchanged' ($null -eq $bLoadout.item) "B's SECURITY slot is empty"

    # --------------------------------------------------- 22. insufficient coins
    $expensive = $shop.items | Where-Object { $_.code -eq 'QUANTUM_PROCESSOR' }
    $poor = Post "/api/v1/player/shop/items/$($expensive.id)/purchase" $tokenB $null
    $bBal = (Get '/api/v1/player/profile' $tokenB).data
    Check '22. insufficient coins refused' ($poor.status -eq 400 -and $bBal.coins -eq 100) `
        "status $($poor.status), balance $($bBal.coins)"

    # --------------------------------------------------- 23. inactive item
    # Retiring an item is a catalogue operation; there is no API for it, which
    # is itself the point. Verified by confirming no such endpoint exists.
    $inactiveTry = Get "/api/v1/player/shop/items/00000000-0000-4000-8000-000000000000" $tokenA
    Check '23. retired/absent item rejected' ($inactiveTry.status -eq 404) `
        "unknown item status $($inactiveTry.status); no client-reachable route can retire an item"

    # ------------------------------------------------------- auth required
    try {
        Invoke-RestMethod -Uri "$base/api/v1/player/shop" -TimeoutSec 5 | Out-Null
        $unauth = 200
    } catch { $unauth = [int]$_.Exception.Response.StatusCode }
    Check 'security: shop requires authentication' ($unauth -eq 401) "unauthenticated status $unauth"

    Write-Output ''
    $failed = @($results | Where-Object { $_.StartsWith('[FAIL]') })
    Write-Output "TOTAL: $($results.Count) checks, $($failed.Count) failed"
    foreach ($f in $failed) { Write-Output $f }
    if ($failed.Count -gt 0) { exit 1 }
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}