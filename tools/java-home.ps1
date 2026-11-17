# Java d'Android Studio pour Gradle, quand JAVA_HOME n'est pas défini (ou pointe vers un dossier vidé).
# Une mise à jour d'Android Studio peut s'installer à côté de l'ancienne (« Android Studio1 ») quand des fichiers
# sont verrouillés, et ne laisser qu'un reste de l'ancien dossier : on prend le premier jbr complet trouvé.
function Set-AndroidJavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'lib\jvm.cfg'))) { return }
    $jbr = Get-ChildItem "$env:ProgramFiles\Android" -Directory -Filter 'Android Studio*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } |
        Where-Object { Test-Path (Join-Path $_ 'lib\jvm.cfg') } |
        Select-Object -First 1
    if (-not $jbr) { throw "Java d'Android Studio introuvable : définir JAVA_HOME." }
    $env:JAVA_HOME = $jbr
}
