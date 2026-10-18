#!/usr/bin/env bash
# Verificações simples antes de empacotar: arquivos obrigatórios e varredura básica de segredos.
set -euo pipefail
cd "$(dirname "$0")/.."
for f in README.md BUILDING.md TESTING.md PRIVACY.md .gitignore .github/workflows/ci.yml installer/CameraLivePro.iss \
         android/settings.gradle.kts windows/CameraLive.Receiver/CameraLive.Receiver.csproj shared/CameraLive.Protocol/Protocol.cs; do
  [ -f "$f" ] || { echo "FALTA: $f"; exit 1; }
done
if grep -rIlE --exclude-dir=.git --exclude=check-repo.sh '(BEGIN (RSA |EC )?PRIVATE KEY|ghp_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}|storePassword\s*=\s*"[^"]+")' . ; then
  echo "POSSÍVEL SEGREDO encontrado nos arquivos acima."; exit 1
fi
if grep -rInE --include='*.kt' --include='*.cs' --include='*.kts' --exclude-dir=.git 'TODO|FIXME|NotImplementedException' . ; then
  echo "Há TODO/FIXME/NotImplemented no código."; exit 1
fi
echo "check-repo: OK"
