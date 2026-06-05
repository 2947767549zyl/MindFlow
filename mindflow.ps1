# PaiCLI Build & Run Helper (PowerShell)
# Usage: .\paicli.ps1 build | .\paicli.ps1 run | .\paicli.ps1 test

param(
    [string]$Action = "help",
    [string]$Args
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = "C:\Users\14736\.jdks\ms-21.0.10"
$env:MAVEN_HOME = "C:\Users\14736\Desktop\apache-maven"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"

switch ($Action) {
    "build" {
        Set-Location $ScriptDir
        mvn clean package -DskipTests
    }
    "run" {
        Set-Location $ScriptDir
        java -jar "target\paicli-1.0-SNAPSHOT.jar"
    }
    "test" {
        Set-Location $ScriptDir
        mvn test
    }
    "mvn" {
        Set-Location $ScriptDir
        mvn $Args
    }
    default {
        Write-Host ""
        Write-Host "PaiCLI Build & Run Helper"
        Write-Host ""
        Write-Host "Usage:"
        Write-Host "  .\paicli.ps1 build         Compile (skip tests)"
        Write-Host "  .\paicli.ps1 run           Run PaiCLI"
        Write-Host "  .\paicli.ps1 test          Run tests"
        Write-Host "  .\paicli.ps1 mvn <args>    Pass args to Maven"
        Write-Host ""
    }
}
