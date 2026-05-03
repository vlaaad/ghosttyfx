if ((Test-Path variable:global:__GhosttyFxState) -and $null -ne $Global:__GhosttyFxState.OriginalPrompt) {
    return
}

$Global:__GhosttyFxState = @{
    Esc = [char]27
    OriginalPrompt = if (Test-Path Function:\prompt) {
        (Get-Command prompt).ScriptBlock
    } else {
        { "PS $($executionContext.SessionState.Path.CurrentLocation)$('>' * ($nestedPromptLevel + 1)) " }
    }
    LastHistoryId = -1
    IsInExecution = $false
    HasPSReadLine = $false
}

function global:prompt {
    Set-StrictMode -Off
    $esc = $Global:__GhosttyFxState.Esc
    $exitCode = [int]!$global:?
    $lastHistoryEntry = Get-History -Count 1
    $result = ""

    if ($Global:__GhosttyFxState.LastHistoryId -ne -1 -and
            ($Global:__GhosttyFxState.HasPSReadLine -eq $false -or $Global:__GhosttyFxState.IsInExecution -eq $true)) {
        $Global:__GhosttyFxState.IsInExecution = $false
        if ($null -ne $lastHistoryEntry -and $lastHistoryEntry.Id -eq $Global:__GhosttyFxState.LastHistoryId) {
            $result += "$esc]133;D`a"
        } else {
            $result += "$esc]133;D;$exitCode`a"
        }
    }

    $result += "$esc]133;A;redraw=0;cl=line`a"
    if ($exitCode -ne 0) {
        Write-Error "failure" -ErrorAction Ignore
    }

    $promptResult = & $Global:__GhosttyFxState.OriginalPrompt
    if ($null -ne $promptResult) {
        $result += $promptResult
    }
    $result += "$esc]133;B`a"

    $Global:__GhosttyFxState.LastHistoryId = $lastHistoryEntry.Id
    $result
}

if (Get-Module -Name PSReadLine) {
    $Global:__GhosttyFxState.HasPSReadLine = $true
    $Global:__GhosttyFxState.OriginalPSConsoleHostReadLine = $function:PSConsoleHostReadLine

    function global:PSConsoleHostReadLine {
        $commandLine = $Global:__GhosttyFxState.OriginalPSConsoleHostReadLine.Invoke()
        $Global:__GhosttyFxState.IsInExecution = $true
        [Console]::Write("$($Global:__GhosttyFxState.Esc)]133;C`a")
        $commandLine
    }
}
