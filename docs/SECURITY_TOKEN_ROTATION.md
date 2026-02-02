# Security: Token Rotation Required

## ⚠️ CRITICAL: Token Exposed in Git History

The Telegram bot token was accidentally committed to Git history in the file `scripts/set-webhook.ps1`.

**Token exposed:** `8361446565:AAFh6-x7ZFhPbiqpYTe68XGmJ0lCFzVPZnQ`

## Immediate Actions Required

### 1. Rotate the Telegram Bot Token

**You MUST create a new bot token immediately:**

1. Open Telegram and search for [@BotFather](https://t.me/botfather)
2. Send `/revoke` command
3. Select your bot
4. Confirm token revocation
5. BotFather will provide a new token
6. Update your `.env` file with the new token:
   ```env
   TELEGRAM_BOT_TOKEN=your_new_token_here
   ```

### 2. Remove File from Git History

The file has been added to `.gitignore`, but it still exists in Git history. To completely remove it:

#### Option A: Using git filter-branch (Recommended for small repos)

```powershell
# Remove the file from all commits
git filter-branch --force --index-filter `
  "git rm --cached --ignore-unmatch scripts/set-webhook.ps1" `
  --prune-empty --tag-name-filter cat -- --all

# Force push to remote (WARNING: This rewrites history)
git push origin --force --all
git push origin --force --tags
```

#### Option B: Using BFG Repo-Cleaner (Recommended for large repos)

1. Download BFG from https://rtyley.github.io/bfg-repo-cleaner/
2. Run:
   ```powershell
   java -jar bfg.jar --delete-files set-webhook.ps1
   git reflog expire --expire=now --all
   git gc --prune=now --aggressive
   git push origin --force --all
   ```

### 3. Verify Removal

```powershell
# Check that the token is no longer in history
git log --all --full-history -S "8361446565" --oneline
# Should return no results

# Verify file is in .gitignore
git check-ignore scripts/set-webhook.ps1
# Should return: scripts/set-webhook.ps1
```

## Prevention

1. ✅ Scripts with secrets are now in `.gitignore`
2. ✅ Always use environment variables for secrets
3. ✅ Never commit `.env` files
4. ✅ Use `.env.example` as a template (without real values)
5. ✅ Review files before committing with `git diff`

## Current Status

- ✅ File added to `.gitignore`
- ✅ Token not found in current working directory
- ⚠️ Token still exists in Git history (commits: a41598d, de00c35)
- ⚠️ **Token rotation required immediately**