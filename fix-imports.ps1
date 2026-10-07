# fix-imports-final.ps1 - PaiSmart Import Path Fix Script (Final Version)
# Usage: .\fix-imports-final.ps1

$ErrorActionPreference = "Stop"
$rootPath = "src\main\java\com\yizhaoqi\smartpai"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  PaiSmart Import Path Final Fix Tool" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Define replacement rules
$replacements = @(
# ===== LlmProviderRouter Migration =====
    @{
        Old = "import com.yizhaoqi.smartpai.service.LlmProviderRouter;"
        New = "import com.yizhaoqi.smartpai.structure.ai.LlmProviderRouter;"
        Description = "LlmProviderRouter -> structure.ai"
    },

    # ===== RateLimitService Migration =====
    @{
        Old = "import com.yizhaoqi.smartpai.service.RateLimitService;"
        New = "import com.yizhaoqi.smartpai.module.admin.service.RateLimitService;"
        Description = "RateLimitService -> module.admin.service"
    }
)

# Process all Java files
$javaFiles = Get-ChildItem -Path $rootPath -Filter "*.java" -Recurse -File
$fixedCount = 0
$totalReplacements = 0

foreach ($file in $javaFiles) {
    $content = Get-Content $file.FullName -Raw -Encoding UTF8
    $originalContent = $content
    $fileReplacements = 0

    foreach ($rule in $replacements) {
        if ($content -match [regex]::Escape($rule.Old)) {
            $content = $content -replace [regex]::Escape($rule.Old), $rule.New
            $fileReplacements++
            $totalReplacements++
            Write-Host "  [$($file.Name)] $($rule.Description)" -ForegroundColor Yellow
        }
    }

    if ($content -ne $originalContent) {
        Set-Content -Path $file.FullName -Value $content -Encoding UTF8 -NoNewline
        $fixedCount++
    }
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Fix Summary" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "Files modified: $fixedCount" -ForegroundColor White
Write-Host "Total replacements: $totalReplacements" -ForegroundColor White
Write-Host ""

if ($fixedCount -gt 0) {
    Write-Host "✓ Import paths fixed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "1. Compile the project: mvn -q -DskipTests compile" -ForegroundColor White
    Write-Host "2. Check for compilation errors" -ForegroundColor White
    Write-Host "3. Test the application" -ForegroundColor White
} else {
    Write-Host "✓ No fixes needed - all imports are correct!" -ForegroundColor Green
}
