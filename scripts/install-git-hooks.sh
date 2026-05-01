#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOK_FILE="$PROJECT_ROOT/.git/hooks/pre-commit"

if [ ! -d "$PROJECT_ROOT/.git/hooks" ]; then
  echo "❌ .git/hooks directory not found. Are you in a git repository?"
  exit 1
fi

cat > "$HOOK_FILE" << 'HOOK'
#!/usr/bin/env bash
# Pre-commit hook — runs quality checks before allowing a commit.
# Installed by scripts/install-git-hooks.sh
set -euo pipefail
"$(git rev-parse --show-toplevel)/scripts/check-before-commit.sh"
HOOK

chmod +x "$HOOK_FILE"
echo "✅ pre-commit hook installed at .git/hooks/pre-commit"
echo ""
echo "   The hook runs ./gradlew check when staged files include"
echo "   Java/Kotlin/Gradle sources."
echo ""
echo "   Bypass with: git commit --no-verify"
echo "   Reinstall:   ./scripts/install-git-hooks.sh"
