# 安全政策 Security Policy

## 回報漏洞 Reporting a Vulnerability

請**勿**透過公開 issue 回報安全漏洞。請改用 GitHub 私密回報管道：

> 本倉庫 **Security → Report a vulnerability**（Private Vulnerability Reporting）

我們會盡快回覆，並在修復完成後協調揭露。

## 關注範圍 Scope

- 驗證流程繞過（選項 A／B、自動降級邏輯）
- Session、權限授予與撤銷的時序問題（TOCTOU、撤銷後殘留）
- 凍結繞過、簽名預言機、Nonce 重放
- 秘密外洩（Discord Bot Token、IP HMAC 密鑰鹽、私鑰）

## 請勿提交秘密 Do Not Commit Secrets

本專案為**公開倉庫**。請確保任何 PR／commit 不含真實 Token、Webhook URL、私鑰或個人資料；
CI 會自動執行秘密掃描（gitleaks）。所有秘密一律以環境變數注入：

- `DISCORD_BOT_TOKEN`
- `IP_HMAC_SECRET`

完整威脅模型見 [`docs/ZeroTrust_2FA_Plan.md`](docs/ZeroTrust_2FA_Plan.md) 第 6 章。
