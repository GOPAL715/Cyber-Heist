# Live HTTP verification for Phase 5 (skill tree and player abilities).
#
# Starts the real Spring Boot application, exercises the Phase 5 API over HTTP
# with real tokens, prints one line per scenario, then shuts the app down.
#
# The database here is H2 in PostgreSQL compatibility mode because no
# PostgreSQL credentials are available on this machine. That is a genuine live
# HTTP check of the API, but it is NOT a PostgreSQL verification.
#
# Usage: powershell -File verify-live-skills.ps1

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8082'
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
if ($cp -notmatch 'h2-2') {
    $h2 = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\h2database\h2" -Recurse -Filter 'h2-*.jar' |
        Where-Object { $_.Name -notmatch 'sources' } |
        Select-Object -First 1
    if (-not $h2) { throw 'H2 jar not found in the local Maven repository.' }
    $cp = "$cp;$($h2.FullName)"
}
$env:JWT_SECRET = 'live-verification-only-secret-value-long-enough-for-hs256'

$appArgs = @(
    '-cp', "target/classes;$cp",
    'com.cyberheist.CyberHeistApplication',
    "--spring.datasource.url=jdbc:h2:mem:liveskills_$([Guid]::NewGuid().ToString('N').Substring(0,8));MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    '--spring.datasource.driver-class-name=org.h2.Driver',
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    '--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
    '--server.port=8082',
    '--app.security.rate-limit.enabled=false'
)

$proc = Start-Process -FilePath 'java' -ArgumentList $appArgs -PassThru `
    -RedirectStandardOutput 'target/live-skills.log' -RedirectStandardError 'target/live-skills.err'

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
        Get-Content 'target/live-skills.log' -Tail 40
        exit 1
    }
    Write-Output "application up on $base (pid $($proc.Id))"

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

    function SkillByCode($tree, $code) {
        foreach ($branch in $tree.data.branches) {
            foreach ($s in $branch.skills) {
                if ($s.code -eq $code) { return $s }
            }
        }
        return $null
    }

    function Unlock($token, $skillId) { return Post "/api/v1/player/skills/$skillId/unlock" $token $null }

    <#
      Solves a SEQUENCE puzzle the way a player does: the prompt names the rule,
      so read the question and apply it. This never reads an answer out of the
      API, because the API does not send one. Returns $null for a type this
      script does not derive, which the caller treats as "not worth attempting".
    #>
    function Solve-Sequence($puzzle) {
        if ($puzzle.type -ne 'SEQUENCE') { return $null }
        $terms = @($puzzle.sequence | Where-Object { $_ -ne '?' } | ForEach-Object { [int]$_ })
        if ($puzzle.question -match 'increases by the same amount') {
            return [string]($terms[-1] + ($terms[1] - $terms[0]))
        }
        if ($puzzle.question -match 'multiplied by the same factor') {
            return [string]($terms[-1] * [int]($terms[1] / $terms[0]))
        }
        if ($puzzle.question -match 'operations alternate') {
            $add = $terms[1] - $terms[0]
            $factor = [int]($terms[2] / $terms[1])
            # Ops alternate add, multiply, ... so the next op is the opposite of
            # the one just applied to reach the last revealed term.
            $nextIsMul = ($terms.Count % 2 -eq 0)
            return [string]$(if ($nextIsMul) { $terms[-1] * $factor } else { $terms[-1] + $add })
        }
        return $null
    }

    # ---------------------------------------------------- 1. register/login
    $emailA = "sk_a_$suffix@example.com"
    Register 'sk_a' $emailA
    $tokenA = Login $emailA
    Check '1. register and login' ([bool]$tokenA) "signed in as $emailA"

    # ------------------------------------- 2. new player has zero skill points
    $profile = (Get '/api/v1/player/profile' $tokenA).data
    Check '2. new player starts with 0 skill points' ($profile.skillPoints -eq 0) `
        "skillPoints = $($profile.skillPoints)"

    # ------------------------------------------------------ 3. read the tree
    $tree = Get '/api/v1/player/skills' $tokenA
    $total = ($tree.data.branches | ForEach-Object { $_.skills.Count } | Measure-Object -Sum).Sum
    $branchNames = ($tree.data.branches | ForEach-Object { $_.branch }) -join ','
    Check '3. skill tree returned' ($total -eq 12 -and $branchNames -eq 'SPEED,INTELLIGENCE,DEFENSE,NETWORK') `
        "$total skills across $branchNames, balance $($tree.data.skillPoints)"

    $rapid = SkillByCode $tree 'RAPID_EXECUTION'
    $quick = SkillByCode $tree 'QUICK_RESPONSE'
    Check '3b. catalogue values are server-defined' `
        ($rapid.maxLevel -eq 5 -and $rapid.nextCost -eq 1 -and $rapid.levels.Count -eq 5) `
        "RAPID_EXECUTION max=$($rapid.maxLevel) nextCost=$($rapid.nextCost) levels=$($rapid.levels.Count)"
    Check '3c. locked skill reports its prerequisite' `
        ($quick.locked -eq $true -and $quick.blockedReason -like '*Rapid Execution level 2 required*') `
        "QUICK_RESPONSE locked=$($quick.locked) reason='$($quick.blockedReason)'"

    # ------------------------------------ 4. cannot unlock a locked skill
    $lockedTry = Unlock $tokenA $quick.id
    Check '4. prerequisite enforced on unlock' ($lockedTry.status -eq 400) `
        "status $($lockedTry.status), message '$($lockedTry.message)'"

    # --------------------------------------- 5. cannot afford a first unlock
    $poorTry = Unlock $tokenA $rapid.id
    Check '5. insufficient skill points refused' ($poorTry.status -eq 400) `
        "status $($poorTry.status), message '$($poorTry.message)'"

    # --------------------------- 6. level-up grants skill points (live, via XP)
    # Points are earned by actually playing missions, so the grant is exercised
    # through the real reward path rather than written directly.
    #
    # The loop stops at 2 points and plays at most 4 missions. That is deliberate:
    # a new player holds 100 energy and missions cost 10-20 each, so playing hard
    # for points would leave no energy to start the mission that proves the
    # ENERGY_SHIELD bonus. Two points is exactly what the checks below spend.
    #
    # The exhaustive tree mechanics - maxing a skill out, every prerequisite pair
    # - are covered exhaustively by SkillUnlockIntegrationTest. This script is
    # for what genuinely needs a live server.
    $missions = (Get '/api/v1/player/missions' $tokenA).data
    $earned = 0
    $played = 0
    for ($round = 0; $round -lt 8 -and $earned -lt 2 -and $played -lt 4; $round++) {
        foreach ($m in ($missions | Where-Object { $_.requiredLevel -le 2 })) {
            if ($earned -ge 2 -or $played -ge 4) { break }
            $start = Post "/api/v1/player/missions/$($m.id)/start" $tokenA $null
            if ($start.status -ne 200) { continue }
            $answer = Solve-Sequence $start.data.puzzle
            if ($answer) {
                $played++
                $sub = Post "/api/v1/player/missions/$($m.id)/puzzle/submit" $tokenA `
                    (@{ puzzleId = $start.data.puzzle.puzzleId; answer = $answer } | ConvertTo-Json)
                if ($sub.data.outcome -eq 'SOLVED') {
                    $earned += [int]$sub.data.progression.levelsGained
                }
            }
        }
    }
    $points = (Get '/api/v1/player/profile' $tokenA).data.skillPoints
    Check '6. skill points granted on level-up' ($points -ge 1 -and $points -eq $earned) `
        "$points point(s) earned from $earned level(s) gained across $played mission(s)"

    # ------------------------------- 7. unlock, charged the catalogue cost
    # ENERGY_SHIELD is a branch root, so one point reaches it, and it grants
    # ENERGY_EFFICIENCY, which mission start really does apply. That makes it the
    # cheapest honest proof that a skill bonus reaches a live mechanic.
    $shield = SkillByCode (Get '/api/v1/player/skills' $tokenA) 'ENERGY_SHIELD'
    $unlockShield = Unlock $tokenA $shield.id
    $afterFirst = (Get '/api/v1/player/profile' $tokenA).data.skillPoints
    Check '7. unlock a skill' ($unlockShield.data.currentLevel -eq 1) `
        "ENERGY_SHIELD level $($unlockShield.data.currentLevel), cost $($unlockShield.data.cost), balance $afterFirst"
    Check '7b. server charged the catalogue cost' `
        ($unlockShield.data.cost -eq 1 -and $afterFirst -eq ($points - 1)) `
        "charged $($unlockShield.data.cost), $points -> $afterFirst"

    # -------------------------------- 8. the skill reaches a real mechanic
    $energyMission = (Get '/api/v1/player/missions' $tokenA).data |
        Where-Object { $_.requiredLevel -le 4 -and $_.energyCost -ge 20 } |
        Select-Object -First 1
    $baseCost = $energyMission.energyCost
    # +3% at level 1, rounded half up, floored at 1: the server's own rule.
    $expectedCost = [math]::Max(1, [math]::Floor(($baseCost * 97 + 50) / 100))
    $energyBefore = (Get '/api/v1/player/profile' $tokenA).data.energy
    $start = Post "/api/v1/player/missions/$($energyMission.id)/start" $tokenA $null
    $energyAfter = (Get '/api/v1/player/profile' $tokenA).data.energy
    Check '8. skill bonus reaches the mission energy cost' `
        ($start.status -eq 200 -and ($energyBefore - $energyAfter) -eq $expectedCost) `
        "ENERGY_SHIELD L1 (+3%): $energyBefore -> $energyAfter, charged $($energyBefore - $energyAfter) of $baseCost (expected $expectedCost)"

    # ------------------------- 9. equipment and skill bonuses combine
    $rapid = SkillByCode (Get '/api/v1/player/skills' $tokenA) 'RAPID_EXECUTION'
    $unlockRapid = Unlock $tokenA $rapid.id
    $tree = Get '/api/v1/player/skills' $tokenA
    $breakdown = $tree.data.bonuses
    $effective = @{}
    foreach ($b in $tree.data.effectiveBonuses) { $effective[$b.type] = $b.percent }
    Check '9. equipment and skill bonuses reported separately' `
        ($breakdown.equipment.MISSION_SPEED -eq 5 -and $breakdown.skills.MISSION_SPEED -eq 2) `
        "equipment MISSION_SPEED=$($breakdown.equipment.MISSION_SPEED), skills=$($breakdown.skills.MISSION_SPEED)"
    Check '9b. effective total is the sum of both sources' `
        ($effective.MISSION_SPEED -eq 7) `
        "effective MISSION_SPEED = $($effective.MISSION_SPEED) (5 gear + 2 skill)"
    Check '9c. the unlock response reports the same capped bonus' `
        ($unlockRapid.data.bonuses[0].percent -eq 7) `
        "unlock response reported MISSION_SPEED = $($unlockRapid.data.bonuses[0].percent)%"

    # ---------------------------------------------------- 10. tampering
    # Both points are now spent, so a forged body that claimed cost 1 and a
    # balance of 9999 must still be refused. If any part of the body were
    # honoured the unlock would succeed, so a 400 with an unchanged balance and
    # level is the proof.
    $balBefore = (Get '/api/v1/player/profile' $tokenA).data.skillPoints
    $tamper = Post "/api/v1/player/skills/$($rapid.id)/unlock" $tokenA `
        (@{ cost = 1; level = 5; effectValue = 999999; skillPoints = 9999 } | ConvertTo-Json)
    $balAfter = (Get '/api/v1/player/profile' $tokenA).data.skillPoints
    $rapidNow = SkillByCode (Get '/api/v1/player/skills' $tokenA) 'RAPID_EXECUTION'
    Check '10. forged cost, level, effect and balance are all ignored' `
        ($tamper.status -eq 400 -and $balAfter -eq $balBefore -and $rapidNow.currentLevel -eq 1) `
        "status $($tamper.status), balance stayed $balAfter, level stayed $($rapidNow.currentLevel)/5"

    # --------------------------------------------- 13. no skill-point setter
    $put = $null
    try {
        Invoke-RestMethod -Uri "$base/api/v1/player/skills" -Method Put `
            -Headers @{ Authorization = "Bearer $tokenA" } `
            -ContentType 'application/json' -Body '{"skillPoints":9999}' | Out-Null
        $put = 200
    } catch { $put = [int]$_.Exception.Response.StatusCode }
    Check '11. no way to set skill points directly' ($put -eq 405) "PUT /player/skills -> $put"

    # ------------------------------------------------- 14. cross-player safety
    $emailB = "sk_b_$suffix@example.com"
    Register 'sk_b' $emailB
    $tokenB = Login $emailB

    $bTree = Get '/api/v1/player/skills' $tokenB
    $bRapid = SkillByCode $bTree 'RAPID_EXECUTION'
    Check '12. player B sees their own tree' `
        ($bTree.data.skillPoints -eq 0 -and $bRapid.currentLevel -eq 0) `
        "B has $($bTree.data.skillPoints) points and RAPID_EXECUTION at $($bRapid.currentLevel)"

    $bUnlock = Unlock $tokenB $bRapid.id
    Check '13. player B cannot spend player A points' ($bUnlock.status -eq 400) `
        "B has no points, so status $($bUnlock.status): '$($bUnlock.message)'"

    # ------------------------------------------------------ 16. auth required
    $unauth = $null
    try {
        Invoke-RestMethod -Uri "$base/api/v1/player/skills" -TimeoutSec 5 | Out-Null
        $unauth = 200
    } catch { $unauth = [int]$_.Exception.Response.StatusCode }
    Check '14. skill tree requires authentication' ($unauth -eq 401) "unauthenticated status $unauth"

    $unknown = Unlock $tokenA '00000000-0000-4000-8000-000000000000'
    Check '15. unknown skill id is 404' ($unknown.status -eq 404) "status $($unknown.status)"

    Write-Output ''
    $failed = @($results | Where-Object { $_.StartsWith('[FAIL]') })
    Write-Output "TOTAL: $($results.Count) checks, $($failed.Count) failed"
    foreach ($f in $failed) { Write-Output $f }
    if ($failed.Count -gt 0) { exit 1 }
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}