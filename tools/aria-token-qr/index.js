#!/usr/bin/env node
// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
//
// aria-token-qr — Run `claude setup-token`, capture the OAuth token, and
//                 display a QR code to scan with ARIA Launcher.
//
// Usage:
//   npx aria-token-qr                   (runs claude setup-token automatically)
//   node tools/aria-token-qr/index.js   (from repo root)
//
// This will open your browser for Anthropic OAuth, then print a QR code
// in your terminal. Point ARIA's QR scanner at it to pair.

const { spawn, execSync } = require("child_process");

function getQrcode() {
  try {
    return require("qrcode-terminal");
  } catch (_) {
    console.log("Installing qrcode-terminal...\n");
    execSync("npm install qrcode-terminal", {
      cwd: __dirname,
      stdio: "ignore",
    });
    return require("qrcode-terminal");
  }
}

function generateQr(token) {
  const qrcode = getQrcode();

  // Build the QR payload — matches what QrTokenScanner.kt expects
  const payload = JSON.stringify({
    access_token: token,
    refresh_token: null,
  });

  if (payload.length > 3000) {
    console.warn(
      `\nWarning: payload is ${payload.length} chars — QR code may be dense.` +
        "\nIf scanning fails, paste the token directly in ARIA's AI setup screen.\n"
    );
  }

  console.log("\nScan this QR code with ARIA's QR scanner:\n");
  qrcode.generate(payload, { small: true }, (code) => {
    console.log(code);
    console.log("Or paste this token directly in ARIA's AI setup screen:");
    console.log(`  ${token}\n`);
  });
}

// Strip ANSI escape codes (cursor movement, colors, clear-line, etc.)
function stripAnsi(str) {
  return str.replace(
    /\x1B(?:\[[0-9;]*[A-Za-z]|\].*?(?:\x07|\x1B\\)|\[[\?]?[0-9;]*[hlsr])/g,
    ""
  );
}

// Token pattern: sk-ant-oat01- followed by base64-ish chars
const TOKEN_RE = /sk-ant-oat01-[A-Za-z0-9_-]{20,}/;

async function main() {
  console.log("ARIA Token QR — Claude credential transfer\n");

  // Check that claude CLI is available
  try {
    execSync("which claude", { stdio: "ignore" });
  } catch (_) {
    console.error(
      "Error: 'claude' command not found.\n\n" +
        "Install Claude Code first:\n" +
        "  npm install -g @anthropic-ai/claude-code\n\n" +
        "Then run this script again."
    );
    process.exit(1);
  }

  console.log("Running claude setup-token...");
  console.log("Your browser will open for Anthropic OAuth.");
  console.log("Complete the sign-in, then come back here.");
  console.log("If prompted, paste the authorization code below.\n");

  // Run claude setup-token with stdin inherited (it may prompt for a code)
  // but capture stdout/stderr to avoid the repeated TUI redraws.
  const child = spawn("claude", ["setup-token"], {
    stdio: ["inherit", "pipe", "pipe"],
  });

  let output = "";

  child.stdout.on("data", (data) => {
    output += data.toString();
  });

  child.stderr.on("data", (data) => {
    output += data.toString();
  });

  child.on("close", (code) => {
    // Clean up the output for display
    const cleaned = stripAnsi(output);

    if (code !== 0) {
      console.error(`\nclaude setup-token exited with code ${code}`);
      // Show any useful error info
      const lines = cleaned.split("\n").filter((l) => l.trim());
      const lastFew = lines.slice(-5).join("\n");
      if (lastFew) console.error(lastFew);
      process.exit(1);
    }

    // Extract the token from output
    const match = cleaned.match(TOKEN_RE);
    if (!match) {
      console.error(
        "\nCould not find an OAuth token in the output.\n" +
          "Expected a token starting with sk-ant-oat01-...\n\n" +
          "You can paste the token manually in ARIA's AI setup screen instead."
      );
      process.exit(1);
    }

    console.log("Token captured successfully!");
    generateQr(match[0]);
  });

  child.on("error", (err) => {
    console.error(`Failed to run claude setup-token: ${err.message}`);
    process.exit(1);
  });
}

main().catch((err) => {
  console.error("Error:", err.message);
  process.exit(1);
});
