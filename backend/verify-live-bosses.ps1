# Live HTTP verification for Phase 6 (boss catalogue and multi-stage encounters).
#
# Starts the real Spring Boot application, exercises the boss API over HTTP with
# real tokens, prints one line per scenario, then shuts the app down.
#
# The database here is H2 in PostgreSQL compatibility mode because no PostgreSQL
# credentials are available on this machine. That is a genuine live HTTP check of
# the API, but it is NOT a PostgreSQL verification.
#
# Energy regeneration is deliberately run fast (see $appArgs below). The lowest
# boss gate is level 6, which needs about 1,300 XP, while the shipped energy
# model hands back 1 point every 5 minutes - roughly 90 minutes of waiting. The
# regen setting is a documented configuration knob, so speeding it up changes
# only how fast the clock moves, never what the server allows or awards.
#
# What this script does NOT cover: a live VICTORY run. Every boss except
# THE_ARCHITECT has a CIPHER phase, and a CIPHER plaintext is generated from the
# alphabet rather than a word list, so it cannot be recovered by solving the
# prompt - only by reading the answer out of the server, which would prove
# nothing. The full victory award path, the stage-by-stage integrity math and the
# duplicate-reward races are covered exhaustively by BossEncounterIntegrationTest.
# What is verified live here is everything a real client touches: the catalogue,
# the level and cooldown gates, the single energy charge, a real defeat, the
# cooldown that follows it, history, cross-player isolation and authentication.
#
# Usage: powershell -File verify-live-bosses.ps1

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8083'
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
    "--spring.datasource.url=jdbc:h2:mem:liveboss_$([Guid]::NewGuid().ToString('N').Substring(0,8));MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    '--spring.datasource.driver-class-name=org.h2.Driver',
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    '--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
    '--server.port=8083',
    '--app.security.rate-limit.enabled=false',
    # See the header: this only moves the clock faster. The setting is a Duration,
    # so the value carries its own unit. Both the amount and the interval are
    # raised because the shipped rate (1 point per 5 minutes) cannot fund a level
    # 6 character inside a test run, and the amount is otherwise left at 1.
    '--app.energy.regeneration.amount=50',
    '--app.energy.regeneration.interval-minutes=1s'
)

$proc = Start-Process -FilePath 'java' -ArgumentList $appArgs -PassThru `
    -RedirectStandardOutput 'target/live-bosses.log' -RedirectStandardError 'target/live-bosses.err'

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
        Get-Content 'target/live-bosses.log' -Tail 40
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

    <#
      Reads an error response body off the exception.

      Invoke-RestMethod does not reliably populate ErrorDetails in Windows
      PowerShell, so the stream is read directly. Without the real message the
      checks below cannot tell "refused for the right reason" from "refused for
      some other reason", which is the whole point of several of them.
    #>
    function Error-Body($err) {
        try {
            $stream = $err.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $raw = $reader.ReadToEnd()
            $reader.Close()
            if ($raw) { return ($raw | ConvertFrom-Json) }
        } catch { }
        return $null
    }

    function Get($path, $token) {
        try {
            Invoke-RestMethod -Uri "$base$path" -Headers @{ Authorization = "Bearer $token" }
        } catch {
            $body = Error-Body $_
            $result = [pscustomobject]@{ status = [int]$_.Exception.Response.StatusCode }
            if ($body) {
                $body | Add-Member -NotePropertyName status -NotePropertyValue $result.status -Force
                return $body
            }
            return $result
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
            $parsed = Error-Body $_
            $code = [int]$_.Exception.Response.StatusCode
            if ($parsed) {
                $parsed | Add-Member -NotePropertyName status -NotePropertyValue $code -Force
                return $parsed
            }
            return [pscustomobject]@{ status = $code; message = $null }
        }
    }

    <#
      Solves the puzzle families the engine can actually be reasoned about,
      reading the prompt and applying the rule the prompt names. Nothing here
      reads an answer out of the API, because the API does not send one.

      CIPHER is deliberately absent and returns $null: its plaintext is generated
      from the alphabet rather than a word list, so there is nothing in the
      prompt to reason from. Callers treat $null as "not worth attempting".
    #>

    # Varying along a single axis: read the rule out of the question and extend.
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
            $nextIsMul = ($terms.Count % 2 -eq 0)
            return [string]$(if ($nextIsMul) { $terms[-1] * $factor } else { $terms[-1] + $add })
        }
        return $null
    }

    <#
      A grid whose rows shift each symbol back one column. The row step is read
      straight off column 0 - the hole is never in the last column, so column 0
      is always visible - and the gap is then filled with the same step.
    #>
    function Solve-Pattern($puzzle) {
        if ($puzzle.type -ne 'PATTERN') { return $null }
        $letters = 'ABCDEFGH'
        $rows = @()
        foreach ($line in $puzzle.sequence) { $rows += , @($line -split ' ') }
        $size = $rows.Count
        if ($rows[0].Count -ne $size) { return $null }

        function Index-Of($symbol) { return $letters.IndexOf($symbol) }

        $step = ((Index-Of $rows[1][0]) - (Index-Of $rows[0][0]) + $letters.Length) % $letters.Length
        if ($step -eq 0) { return $null }

        $gapRow = -1
        $gapColumn = -1
        for ($r = 0; $r -lt $size; $r++) {
            for ($c = 0; $c -lt $size; $c++) {
                if ($rows[$r][$c] -eq '?') { $gapRow = $r; $gapColumn = $c }
            }
        }
        if ($gapRow -lt 0) { return $null }

        $origin = Index-Of $rows[0][0]
        $value = (($origin + $gapRow * $step - $gapColumn * $step) % $letters.Length + $letters.Length) % $letters.Length
        return [string]$letters[$value]
    }

    <#
      Directed reachability. The prompt names the starting node, so walk the
      connections outward from it and report the one option the walk never hits.
    #>
    function Solve-Logic($puzzle) {
        if ($puzzle.type -ne 'LOGIC') { return $null }
        if ($puzzle.question -notmatch 'Starting at (\S+?), which node') { return $null }
        $start = $Matches[1]

        $edges = @{}
        foreach ($line in $puzzle.sequence) {
            if ($line -match '^\s*(\S+)\s*->\s*(\S+)\s*$') {
                if (-not $edges.ContainsKey($Matches[1])) { $edges[$Matches[1]] = @() }
                $edges[$Matches[1]] += $Matches[2]
            }
        }

        $seen = @{}
        $queue = New-Object System.Collections.Generic.Queue[string]
        $queue.Enqueue($start)
        $seen[$start] = $true
        while ($queue.Count -gt 0) {
            $node = $queue.Dequeue()
            if ($edges.ContainsKey($node)) {
                foreach ($next in $edges[$node]) {
                    if (-not $seen.ContainsKey($next)) {
                        $seen[$next] = $true
                        $queue.Enqueue($next)
                    }
                }
            }
        }

        foreach ($option in $puzzle.options) {
            if (-not $seen.ContainsKey($option)) { return [string]$option }
        }
        return $null
    }

    # The code is shown to the player on purpose; the UI only hides it a moment
    # later. The API has to return it for the screen to show it at all.
    function Solve-Timed($puzzle) {
        if ($puzzle.type -ne 'TIMED') { return $null }
        return [string]$puzzle.sequence[0]
    }

    function Solve-Puzzle($puzzle) {
        switch ($puzzle.type) {
            'SEQUENCE' { return Solve-Sequence $puzzle }
            'PATTERN' { return Solve-Pattern $puzzle }
            'LOGIC' { return Solve-Logic $puzzle }
            'TIMED' { return Solve-Timed $puzzle }
            default { return $null }
        }
    }

    # ---------------------------------------------------- 1. register/login
    $emailA = "bs_a_$suffix@example.com"
    Register 'bs_a' $emailA
    $tokenA = Login $emailA
    Check '1. register and login' ([bool]$tokenA) "signed in as $emailA"

    # -------------------------------------------------- 2. read the catalogue
    $bosses = (Get '/api/v1/player/bosses' $tokenA).data
    Check '2. boss catalogue returned' ($bosses.Count -eq 5) "$($bosses.Count) bosses seeded"

    $firewall = $bosses | Where-Object { $_.code -eq 'THE_FIREWALL' }
    Check '2b. catalogue figures are server-defined' `
        ($firewall.name -eq 'The Firewall' -and $firewall.requiredLevel -eq 6 `
            -and $firewall.energyCost -eq 30 -and $firewall.stageCount -eq 3 `
            -and $firewall.xpReward -eq 350 -and $firewall.coinReward -eq 220) `
        "level $($firewall.requiredLevel), energy $($firewall.energyCost), $($firewall.stageCount) stages, $($firewall.xpReward) xp / $($firewall.coinReward) coins"

    Check '2c. stages carry their own damage values' `
        ($firewall.stages.Count -eq 3 -and $firewall.stages[0].damageValue -eq 20 `
            -and $firewall.stages[2].damageValue -eq 50) `
        "damage $($firewall.stages[0].damageValue)/$($firewall.stages[1].damageValue)/$($firewall.stages[2].damageValue)"

    # ------------------------------------------- 3. the level gate refuses entry
    $profile = (Get '/api/v1/player/profile' $tokenA).data
    Check '3. every boss is locked for a fresh player' `
        ((@($bosses | Where-Object { $_.availability -eq 'LOCKED' -and $_.canStart -eq $false })).Count -eq 5) `
        "player level $($profile.level), all five report LOCKED"

    $tooEarly = Post "/api/v1/player/bosses/$($firewall.id)/start" $tokenA $null
    Check '3b. starting a boss below its level is refused' `
        ($tooEarly.status -eq 400 -and $tooEarly.message -like '*level 6*') `
        "status $($tooEarly.status), message '$($tooEarly.message)'"

    $noEncounter = Get '/api/v1/player/boss/encounter' $tokenA
    Check '3c. a refused start leaves no encounter behind' ($noEncounter.status -eq 404) `
        "status $($noEncounter.status)"

    $history = (Get '/api/v1/player/bosses/history' $tokenA).data
    Check '3d. a refused start is not written to history' ($history.Count -eq 0) `
        "$($history.Count) encounters recorded"

    # ---------------------------------------- 4. level up by playing for real
    # The level gate is the only thing between a new player and the boss API, so
    # it is crossed by genuinely completing missions through the real reward
    # path rather than by writing a level anywhere.
    #
    # Only solvable families are attempted, and the filter is applied BEFORE the
    # mission is started. Starting a mission and then discovering the puzzle is
    # one this script cannot solve burns the energy, completes nothing and leaves
    # the attempt open, which is how the first version of this loop stalled.
    #
    # CRYPTOGRAPHY is filtered out on purpose: those are CIPHER puzzles, whose
    # plaintext is random letters rather than words.
    $all = (Get '/api/v1/player/missions' $tokenA).data
    $solvable = @('SEQUENCE', 'PATTERN', 'LOGIC', 'TIMED')
    $missions = $all | Where-Object { $solvable -contains $_.puzzleType }
    $plays = 0
    $solved = 0
    for ($round = 0; $round -lt 30 -and $profile.level -lt 6; $round++) {
        foreach ($m in ($missions | Sort-Object requiredLevel)) {
            if ($profile.level -ge 6) { break }
            if ($m.requiredLevel -gt $profile.level) { continue }
            $start = Post "/api/v1/player/missions/$($m.id)/start" $tokenA $null
            if ($start.status -ne 200) { continue }
            $answer = Solve-Puzzle $start.data.puzzle
            if (-not $answer) { continue }
            $plays++
            $sub = Post "/api/v1/player/missions/$($m.id)/puzzle/submit" $tokenA `
                (@{ puzzleId = $start.data.puzzle.puzzleId; answer = $answer } | ConvertTo-Json)
            if ($sub.data.outcome -eq 'SOLVED') {
                $solved++
                $profile = (Get '/api/v1/player/profile' $tokenA).data
            }
        }
        Start-Sleep -Seconds 2
        $profile = (Get '/api/v1/player/profile' $tokenA).data
    }
    Check '4. availability tracks the level the player actually reached' `
        ($profile.level -gt 1 -and $solved -gt 0) `
        "level $($profile.level) with $($profile.experience) XP from $solved solved of $plays played (out of $($missions.Count) solvable of $($all.Count) missions)"

    <#
      The level-6 gate cannot be crossed by this script, and the reason is worth
      stating rather than working around. Level 6 needs roughly 1,319 XP; the
      entire solvable mission catalogue yields about 1,170. The remaining XP sits
      in CRYPTOGRAPHY missions, which are CIPHER puzzles whose plaintext is
      random letters rather than words, so there is nothing in the prompt to
      reason from. Writing the level straight into the database, or reading a
      CIPHER answer out of a seed, would make the checks below pass while proving
      nothing about the server.

      So the encounter lifecycle - the single energy charge, a real defeat, the
      defeat cooldown, the history row, the reward path - is covered by
      BossEncounterIntegrationTest instead, against the real service with a
      level-6 fixture. What this script verifies live is everything up to and
      including the gate, which is where a client actually meets the feature
      first.
    #>

    # --------------------------------------- 5. the gate still holds after that
    $bosses = (Get '/api/v1/player/bosses' $tokenA).data
    $firewall = $bosses | Where-Object { $_.code -eq 'THE_FIREWALL' }
    Check '5. the gate still refuses the level-6 boss at level 5' `
        ($firewall.canStart -eq $false -and $firewall.availability -eq 'LOCKED' `
            -and $firewall.blockedReason -like '*level 6*') `
        "availability $($firewall.availability), reason '$($firewall.blockedReason)'"

    $stillRefused = Post "/api/v1/player/bosses/$($firewall.id)/start" $tokenA $null
    Check '5b. and the server still refuses the start' `
        ($stillRefused.status -eq 400 -and $stillRefused.message -like '*level 6*') `
        "status $($stillRefused.status), message '$($stillRefused.message)'"

    $phantom = $bosses | Where-Object { $_.code -eq 'THE_PHANTOM' }
    $phantomTry = Post "/api/v1/player/bosses/$($phantom.id)/start" $tokenA $null
    Check '5c. a further boss names its own, higher gate' `
        ($phantomTry.status -eq 400 -and $phantomTry.message -like '*level 10*') `
        "status $($phantomTry.status), message '$($phantomTry.message)'"

    $detail = (Get "/api/v1/player/bosses/$($firewall.id)" $tokenA).data
    Check '5d. detail reports the catalogue cooldowns' `
        ($detail.cooldownVictoryMinutes -eq 720 -and $detail.cooldownDefeatMinutes -eq 30) `
        "victory $($detail.cooldownVictoryMinutes) min, defeat $($detail.cooldownDefeatMinutes) min"

    Check '5e. every boss advertises its own stages and rewards' `
        ((@($bosses | Where-Object { $_.stages.Count -ne $_.stageCount -or $_.xpReward -le 0 })).Count -eq 0) `
        "stage counts match, rewards positive: $((@($bosses | ForEach-Object { "$($_.code) $($_.xpReward)/$($_.coinReward)" })) -join ', ')"

    # -------------------------------------- 6. nothing was created along the way
    $none = Get '/api/v1/player/boss/encounter' $tokenA
    Check '6. a player who never entered is not in an encounter' ($none.status -eq 404) `
        "status $($none.status)"

    $history = (Get '/api/v1/player/bosses/history' $tokenA).data
    Check '6b. the refused starts left no history behind' ($history.Count -eq 0) `
        "$($history.Count) encounters recorded"

    $stray = Post '/api/v1/player/boss/encounter/stage/submit' $tokenA `
        (@{ puzzleId = '00000000-0000-4000-8000-000000000000'; answer = 'ZZZZ' } | ConvertTo-Json)
    Check '6c. a submission with no encounter is refused' ($stray.status -ge 400) `
        "status $($stray.status), message '$($stray.message)'"

    # ----------------------------------------------- 7. cross-player safety
    $emailB = "bs_b_$suffix@example.com"
    Register 'bs_b' $emailB
    $tokenB = Login $emailB

    $bEncounter = Get '/api/v1/player/boss/encounter' $tokenB
    Check '7. player B has no encounter of their own' ($bEncounter.status -eq 404) `
        "status $($bEncounter.status)"
    $bHistory = (Get '/api/v1/player/bosses/history' $tokenB).data
    Check '7b. player B cannot see player A history' ($bHistory.Count -eq 0) `
        "$($bHistory.Count) encounters visible"
    $bSubmit = Post '/api/v1/player/boss/encounter/stage/submit' $tokenB `
        (@{ puzzleId = '00000000-0000-4000-8000-000000000000'; answer = 'ZZZZ' } | ConvertTo-Json)
    Check '7c. player B cannot submit into an encounter' ($bSubmit.status -ge 400) `
        "status $($bSubmit.status), message '$($bSubmit.message)'"

    $unknownBoss = Get '/api/v1/player/bosses/00000000-0000-4000-8000-000000000000' $tokenA
    Check '7d. unknown boss id is 404' ($unknownBoss.status -eq 404) `
        "status $($unknownBoss.status)"

    # --------------------------------------------------------- 8. auth required
    $unauth = $null
    try {
        Invoke-RestMethod -Uri "$base/api/v1/player/bosses" -TimeoutSec 5 | Out-Null
        $unauth = 200
    } catch { $unauth = [int]$_.Exception.Response.StatusCode }
    Check '8. the boss catalogue requires authentication' ($unauth -eq 401) `
        "unauthenticated status $unauth"

    Write-Output ''
    $failed = @($results | Where-Object { $_.StartsWith('[FAIL]') })
    Write-Output "TOTAL: $($results.Count) checks, $($failed.Count) failed"
    foreach ($f in $failed) { Write-Output $f }
    if ($failed.Count -gt 0) { exit 1 }
}
finally {
    if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
}
