param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [int[]]$EventCounts = @(500, 1000),
    [int[]]$WorkerCounts = @(1, 2, 4),
    [ValidateRange(1, 100)]
    [int]$SeedVus = 50,
    [ValidateRange(1, 5)]
    [int]$Repetitions = 3
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$workerEnabledVariable = "HOOK_RELAY_WORKER_ENABLED"
$receiverDelayVariable = "WEBHOOK_DELAY_MILLIS"
$originalWorkerEnabled = [Environment]::GetEnvironmentVariable($workerEnabledVariable, "Process")
$originalReceiverDelay = [Environment]::GetEnvironmentVariable($receiverDelayVariable, "Process")
$results = @()

$EventCounts = @($EventCounts | Sort-Object -Unique)
$WorkerCounts = @($WorkerCounts | Sort-Object -Unique)
if ($EventCounts.Count -eq 0 -or @($EventCounts | Where-Object { $_ -lt 100 -or $_ -gt 2000 }).Count -gt 0) {
    throw "EventCounts must contain values between 100 and 2000"
}
if ($WorkerCounts.Count -eq 0 -or @($WorkerCounts | Where-Object { $_ -lt 1 -or $_ -gt 8 }).Count -gt 0) {
    throw "WorkerCounts must contain values between 1 and 8"
}
if ($WorkerCounts -notcontains 1) {
    throw "WorkerCounts must include 1 to calculate scaling speedup"
}

function Set-ProcessEnvironment {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Invoke-DatabaseScalar {
    param(
        [Parameter(Mandatory)]
        [string]$Query
    )

    $result = docker compose exec -T postgres `
        psql -U hook_relay -d hook_relay -tAc $Query
    if ($LASTEXITCODE -ne 0) {
        throw "Database query failed"
    }
    $values = @(
        $result |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -ne "" }
    )
    if ($values.Count -ne 1) {
        throw "Expected one database value, but found $($values.Count)"
    }
    return [string]$values[0]
}

function Assert-NoActiveDeliveries {
    $activeDeliveries = [int](Invoke-DatabaseScalar -Query @"
SELECT count(*)
FROM webhook_deliveries
WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');
"@)
    if ($activeDeliveries -ne 0) {
        throw "Expected no active deliveries before measurement, but found $activeDeliveries"
    }
}

function Wait-ApplicationHealthy {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health"
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            Start-Sleep -Seconds 1
            continue
        }
        Start-Sleep -Seconds 1
    }
    throw "Application did not become healthy within 60 seconds"
}

function Get-OutcomeMetricValue {
    param(
        [Parameter(Mandatory)]
        [string]$Content,
        [Parameter(Mandatory)]
        [string]$MetricName,
        [Parameter(Mandatory)]
        [string]$Outcome
    )

    $pattern = "(?m)^$([regex]::Escape($MetricName))\{[^}]*outcome=`"$([regex]::Escape($Outcome))`"[^}]*\}\s+([0-9.eE+-]+)$"
    $matches = [regex]::Matches($Content, $pattern)
    if ($matches.Count -eq 0) {
        return 0.0
    }
    $sum = 0.0
    foreach ($match in $matches) {
        $sum += [double]::Parse(
            $match.Groups[1].Value,
            [Globalization.CultureInfo]::InvariantCulture
        )
    }
    return $sum
}

function Get-UntaggedMetricValue {
    param(
        [Parameter(Mandatory)]
        [string]$Content,
        [Parameter(Mandatory)]
        [string]$MetricName
    )

    $pattern = "(?m)^$([regex]::Escape($MetricName))\s+([0-9.eE+-]+)$"
    $match = [regex]::Match($Content, $pattern)
    if (-not $match.Success) {
        return 0.0
    }
    return [double]::Parse(
        $match.Groups[1].Value,
        [Globalization.CultureInfo]::InvariantCulture
    )
}

function Get-ApplicationProcessingCount {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus"
    return Get-OutcomeMetricValue `
        -Content $response.Content `
        -MetricName "hookrelay_delivery_processing_seconds_count" `
        -Outcome "succeeded"
}

function Remove-ScaleWorkers {
    docker compose --profile worker-scale rm --stop --force worker | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Scale worker cleanup failed"
    }
}

function Start-ScaleWorkers {
    param(
        [Parameter(Mandatory)]
        [int]$WorkerCount
    )

    docker compose --profile worker-scale up `
        --detach `
        --force-recreate `
        --no-deps `
        --scale "worker=$WorkerCount" `
        --wait `
        worker
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start $WorkerCount scale workers"
    }
}

function Get-WorkerMetricSnapshots {
    param(
        [Parameter(Mandatory)]
        [int]$ExpectedWorkerCount
    )

    $containerIds = @(
        docker compose --profile worker-scale ps --status running --quiet worker |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -ne "" }
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Worker container query failed"
    }
    if ($containerIds.Count -ne $ExpectedWorkerCount) {
        throw "Expected $ExpectedWorkerCount running workers, but found $($containerIds.Count)"
    }

    $snapshots = @()
    foreach ($containerId in $containerIds) {
        $containerName = docker inspect --format "{{.Name}}" $containerId
        if ($LASTEXITCODE -ne 0) {
            throw "Worker container name query failed"
        }
        $metrics = docker exec $containerId `
            wget -q -O - http://127.0.0.1:8080/actuator/prometheus
        if ($LASTEXITCODE -ne 0) {
            throw "Worker metrics query failed for $containerId"
        }
        $content = $metrics -join "`n"
        $snapshots += [pscustomobject]@{
            Name = ([string]$containerName).Trim().TrimStart("/")
            Claimed = [int][Math]::Round((Get-UntaggedMetricValue `
                -Content $content `
                -MetricName "hookrelay_delivery_claimed_total"))
            Processed = [int][Math]::Round((Get-OutcomeMetricValue `
                -Content $content `
                -MetricName "hookrelay_delivery_processing_seconds_count" `
                -Outcome "succeeded"))
            ClaimQueries = [int][Math]::Round((Get-OutcomeMetricValue `
                -Content $content `
                -MetricName "hookrelay_delivery_claim_seconds_count" `
                -Outcome "succeeded"))
            ClaimSeconds = Get-OutcomeMetricValue `
                -Content $content `
                -MetricName "hookrelay_delivery_claim_seconds_sum" `
                -Outcome "succeeded"
        }
    }
    return $snapshots
}

function Get-RunCounts {
    param(
        [Parameter(Mandatory)]
        [string]$EventType
    )

    $query = @"
SELECT concat_ws('|',
    count(DISTINCT e.id),
    count(*),
    count(*) FILTER (WHERE d.status = 'PENDING'),
    count(*) FILTER (WHERE d.status = 'PROCESSING'),
    count(*) FILTER (WHERE d.status = 'SUCCEEDED'),
    count(*) FILTER (WHERE d.status IN ('RETRY_WAIT', 'FAILED', 'DEAD_LETTER')),
    coalesce(sum(d.attempt_count), 0)
)
FROM webhook_deliveries d
JOIN webhook_events e ON e.id = d.event_id
WHERE e.event_type = '$EventType';
"@
    $values = (Invoke-DatabaseScalar -Query $query) -split '\|'
    if ($values.Count -ne 7) {
        throw "Expected seven event and delivery count values, but found $($values.Count)"
    }
    return @{
        Events = [int]$values[0]
        Total = [int]$values[1]
        Pending = [int]$values[2]
        Processing = [int]$values[3]
        Succeeded = [int]$values[4]
        Failure = [int]$values[5]
        AttemptCount = [int]$values[6]
    }
}

function Wait-RunCompleted {
    param(
        [Parameter(Mandatory)]
        [string]$EventType,
        [Parameter(Mandatory)]
        [int]$EventCount
    )

    for ($attempt = 0; $attempt -lt 600; $attempt++) {
        $counts = Get-RunCounts -EventType $EventType
        if ($counts.Failure -ne 0) {
            throw "Unexpected delivery failure: $($counts.Failure)"
        }
        if ($counts.Events -eq $EventCount `
                -and $counts.Total -eq $EventCount `
                -and $counts.Succeeded -eq $EventCount `
                -and $counts.AttemptCount -eq $EventCount) {
            return $counts
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Worker scaling run did not finish within 300 seconds"
}

function Invoke-SeedLoad {
    param(
        [Parameter(Mandatory)]
        [string]$EventType,
        [Parameter(Mandatory)]
        [string]$RunId,
        [Parameter(Mandatory)]
        [int]$EventCount
    )

    $loadTestPath = Join-Path $repositoryRoot "load-tests"
    docker run --rm `
        --network hook-relay_default `
        --mount "type=bind,source=$loadTestPath,target=/scripts,readonly" `
        -e BASE_URL=http://app:8080 `
        -e EVENT_TYPE=$EventType `
        -e RUN_ID=$RunId `
        -e EVENT_COUNT=$EventCount `
        -e VUS=$SeedVus `
        grafana/k6:2.1.0 run --quiet /scripts/worker-throughput-seed.js
    if ($LASTEXITCODE -ne 0) {
        throw "Worker scaling seed load failed"
    }
}

function Wait-ReceiverRequestCount {
    param(
        [Parameter(Mandatory)]
        [string]$RunId,
        [Parameter(Mandatory)]
        [int]$ExpectedCount
    )

    $observedCount = 0
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $receiverLogs = docker compose logs --no-log-prefix webhook-receiver
        if ($LASTEXITCODE -ne 0) {
            throw "Webhook receiver log query failed"
        }
        $observedCount = @($receiverLogs | Select-String -SimpleMatch $RunId).Count
        if ($observedCount -eq $ExpectedCount) {
            return $observedCount
        }
        if ($observedCount -gt $ExpectedCount) {
            throw "Receiver request count exceeded expectation: expected=$ExpectedCount actual=$observedCount"
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Receiver request count did not reach expectation within 20 seconds: expected=$ExpectedCount actual=$observedCount"
}

function Get-Median {
    param(
        [Parameter(Mandatory)]
        [double[]]$Values
    )

    $sorted = @($Values | Sort-Object)
    if ($sorted.Count % 2 -eq 1) {
        return $sorted[[Math]::Floor($sorted.Count / 2)]
    }
    return ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2
}

Push-Location $repositoryRoot
try {
    Set-ProcessEnvironment -Name $workerEnabledVariable -Value "false"
    Set-ProcessEnvironment -Name $receiverDelayVariable -Value "0"

    Remove-ScaleWorkers
    docker compose stop app
    if ($LASTEXITCODE -ne 0) {
        throw "Application stop failed"
    }

    docker compose up --detach --wait postgres
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL startup failed"
    }

    docker compose up --detach --force-recreate --no-deps --wait webhook-receiver
    if ($LASTEXITCODE -ne 0) {
        throw "Webhook receiver startup failed"
    }

    Assert-NoActiveDeliveries
    docker compose build app
    if ($LASTEXITCODE -ne 0) {
        throw "Application image build failed"
    }
    docker compose up --detach --force-recreate --no-deps --wait app
    if ($LASTEXITCODE -ne 0) {
        throw "Application startup failed"
    }
    Wait-ApplicationHealthy

    if ((Get-ApplicationProcessingCount) -ne 0) {
        throw "API application processed deliveries even though its worker is disabled"
    }

    foreach ($eventCount in $EventCounts) {
        foreach ($workerCount in $WorkerCounts) {
            for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
                Remove-ScaleWorkers
                Assert-NoActiveDeliveries

                $runId = [Guid]::NewGuid().ToString("N")
                $eventType = "load.worker.scale.$runId"
                $subscriptionBody = @{
                    name = "worker-scale-$runId"
                    endpointUrl = "http://webhook-receiver:8081/webhooks/delay"
                    eventTypes = @($eventType)
                } | ConvertTo-Json -Compress
                $subscription = Invoke-RestMethod `
                    -Method Post `
                    -Uri "$BaseUrl/api/v1/subscriptions" `
                    -ContentType "application/json" `
                    -Body $subscriptionBody

                Write-Host ""
                Write-Host "Worker scaling run started"
                Write-Host "events=$eventCount"
                Write-Host "workers=$workerCount"
                Write-Host "repetition=$repetition"
                Write-Host "eventType=$eventType"

                $seed = [Diagnostics.Stopwatch]::StartNew()
                Invoke-SeedLoad `
                    -EventType $eventType `
                    -RunId $runId `
                    -EventCount $eventCount
                $seed.Stop()

                $seededCounts = Get-RunCounts -EventType $eventType
                if ($seededCounts.Events -ne $eventCount `
                        -or $seededCounts.Total -ne $eventCount `
                        -or $seededCounts.Pending -ne $eventCount `
                        -or $seededCounts.AttemptCount -ne 0) {
                    throw "Seed count mismatch: events=$($seededCounts.Events), deliveries=$($seededCounts.Total), pending=$($seededCounts.Pending), attempts=$($seededCounts.AttemptCount)"
                }

                $wallClock = [Diagnostics.Stopwatch]::StartNew()
                Start-ScaleWorkers -WorkerCount $workerCount
                $completedCounts = Wait-RunCompleted `
                    -EventType $eventType `
                    -EventCount $eventCount
                $wallClock.Stop()

                $workerMetrics = @(Get-WorkerMetricSnapshots -ExpectedWorkerCount $workerCount)
                $claimedTotal = ($workerMetrics | Measure-Object -Property Claimed -Sum).Sum
                $processedTotal = ($workerMetrics | Measure-Object -Property Processed -Sum).Sum
                $claimQueries = ($workerMetrics | Measure-Object -Property ClaimQueries -Sum).Sum
                $claimSeconds = ($workerMetrics | Measure-Object -Property ClaimSeconds -Sum).Sum
                if ([int]$claimedTotal -ne $eventCount -or [int]$processedTotal -ne $eventCount) {
                    throw "Worker metric count mismatch: claimed=$claimedTotal processed=$processedTotal expected=$eventCount"
                }
                if (@($workerMetrics | Where-Object { $_.Claimed -eq 0 }).Count -ne 0) {
                    throw "At least one scale worker did not claim a delivery"
                }
                if ((Get-ApplicationProcessingCount) -ne 0) {
                    throw "API application processed deliveries even though its worker is disabled"
                }

                $timing = Invoke-DatabaseScalar -Query @"
SELECT concat_ws('|',
    round(extract(epoch FROM (max(a.finished_at) - min(a.started_at)))::numeric, 6),
    count(*)
)
FROM webhook_delivery_attempts a
JOIN webhook_deliveries d ON d.id = a.delivery_id
JOIN webhook_events e ON e.id = d.event_id
WHERE e.event_type = '$eventType';
"@
                $timingValues = $timing -split '\|'
                if ($timingValues.Count -ne 2 -or [int]$timingValues[1] -ne $eventCount) {
                    throw "Attempt timing count mismatch: $timing"
                }
                $processingSeconds = [double]::Parse(
                    $timingValues[0],
                    [Globalization.CultureInfo]::InvariantCulture
                )
                if ($processingSeconds -le 0) {
                    throw "Processing duration must be positive"
                }

                $receiverRequestCount = Wait-ReceiverRequestCount `
                    -RunId $runId `
                    -ExpectedCount $eventCount

                $throughput = $eventCount / $processingSeconds
                $claimAverageMilliseconds = if ($claimQueries -gt 0) {
                    ($claimSeconds / $claimQueries) * 1000
                } else {
                    0.0
                }
                $distribution = ($workerMetrics | ForEach-Object { "$($_.Name):$($_.Claimed)" }) -join ","
                $result = [pscustomobject]@{
                    Events = $eventCount
                    Workers = $workerCount
                    Repetition = $repetition
                    SubscriptionId = $subscription.id
                    EventType = $eventType
                    SeedSeconds = [Math]::Round($seed.Elapsed.TotalSeconds, 2)
                    WallSeconds = [Math]::Round($wallClock.Elapsed.TotalSeconds, 2)
                    ProcessingSeconds = [Math]::Round($processingSeconds, 3)
                    Throughput = [Math]::Round($throughput, 2)
                    ClaimQueries = [int]$claimQueries
                    ClaimSeconds = $claimSeconds
                    ClaimAverageMilliseconds = [Math]::Round($claimAverageMilliseconds, 3)
                    Distribution = $distribution
                    ReceiverRequests = $receiverRequestCount
                    Succeeded = $completedCounts.Succeeded
                }
                $results += $result

                Write-Host ""
                Write-Host "Worker scaling run completed"
                Write-Host "events=$($result.Events)"
                Write-Host "workers=$($result.Workers)"
                Write-Host "repetition=$($result.Repetition)"
                Write-Host "seedSeconds=$($result.SeedSeconds)"
                Write-Host "wallSeconds=$($result.WallSeconds)"
                Write-Host "processingSeconds=$($result.ProcessingSeconds)"
                Write-Host "throughputPerSecond=$($result.Throughput)"
                Write-Host "claimQueries=$($result.ClaimQueries)"
                Write-Host "claimAverageMilliseconds=$($result.ClaimAverageMilliseconds)"
                Write-Host "workerDistribution=$($result.Distribution)"
                Write-Host "finalCounts=events:$eventCount,deliveries:$($result.Succeeded),attempts:$eventCount,receiverRequests:$($result.ReceiverRequests)"

                Remove-ScaleWorkers
            }
        }
    }
} catch {
    Write-Warning "Worker scaling run failed: $($_.Exception.Message)"
    Write-Host ""
    Write-Host "Scale worker logs (last 100 lines)"
    docker compose --profile worker-scale logs --tail 100 --no-color worker | Out-Host
    Write-Host ""
    Write-Host "Webhook receiver logs (last 100 lines)"
    docker compose logs --tail 100 --no-color webhook-receiver | Out-Host
    throw
} finally {
    try {
        Remove-ScaleWorkers
    } catch {
        Write-Warning "Failed to remove scale workers: $($_.Exception.Message)"
    }
    Set-ProcessEnvironment -Name $workerEnabledVariable -Value $originalWorkerEnabled
    Set-ProcessEnvironment -Name $receiverDelayVariable -Value $originalReceiverDelay
    docker compose up --detach --force-recreate --no-deps --wait webhook-receiver | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Failed to restore the default webhook receiver settings"
    }
    docker compose up --detach --force-recreate --no-deps --wait app | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Failed to restore the default application settings"
    }
    Pop-Location
}

$expectedResults = $EventCounts.Count * $WorkerCounts.Count * $Repetitions
if ($results.Count -eq $expectedResults) {
    Write-Host ""
    Write-Host "Worker scaling measurement completed"
    Write-Host "runs=$expectedResults"
    foreach ($eventCount in $EventCounts) {
        $baseline = @($results | Where-Object { $_.Events -eq $eventCount -and $_.Workers -eq 1 })
        $baselineAverage = ($baseline | Measure-Object -Property Throughput -Average).Average
        foreach ($workerCount in $WorkerCounts) {
            $group = @($results | Where-Object { $_.Events -eq $eventCount -and $_.Workers -eq $workerCount })
            $throughputs = @($group | ForEach-Object { [double]$_.Throughput })
            $average = ($throughputs | Measure-Object -Average).Average
            $minimum = ($throughputs | Measure-Object -Minimum).Minimum
            $maximum = ($throughputs | Measure-Object -Maximum).Maximum
            $median = Get-Median -Values $throughputs
            $speedup = $average / $baselineAverage
            $efficiency = ($speedup / $workerCount) * 100
            $claimQueryCount = ($group | Measure-Object -Property ClaimQueries -Sum).Sum
            $claimSeconds = ($group | Measure-Object -Property ClaimSeconds -Sum).Sum
            $claimAverageMilliseconds = if ($claimQueryCount -gt 0) {
                ($claimSeconds / $claimQueryCount) * 1000
            } else {
                0.0
            }

            Write-Host "summary=events:$eventCount,workers:$workerCount,average:$([Math]::Round($average, 2)),median:$([Math]::Round($median, 2)),minimum:$([Math]::Round($minimum, 2)),maximum:$([Math]::Round($maximum, 2)),speedup:$([Math]::Round($speedup, 2)),efficiencyPercent:$([Math]::Round($efficiency, 1)),claimAverageMilliseconds:$([Math]::Round($claimAverageMilliseconds, 3))"
        }
    }
}
