---
name: stock-quote
description: Check stock prices and market performance. Use when the user asks about stocks, their portfolio, market conditions, or proactively in the morning to show price updates.
metadata:
  author: aria
  version: "1.0"
  aria-trigger: heartbeat
---

# Stock Quote

Check current stock prices using free public APIs.

## Configuration
The user's watchlist symbols are stored in skill config key `symbols` (comma-separated, e.g., "AAPL,GOOGL,TSLA").
If no symbols are configured, ask the user what stocks they want to track.

## How to check a single stock
Fetch price data for a symbol:
`https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1d&range=1d`

From the response, extract:
- `chart.result[0].meta.regularMarketPrice` - current price
- `chart.result[0].meta.chartPreviousClose` - previous close

Calculate percent change: `((price - previousClose) / previousClose) * 100`

If Yahoo Finance is unavailable, try the Alpha Vantage demo endpoint:
`https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol={symbol}&apikey=demo`

## How to check multiple stocks
Fetch each symbol individually. Combine results into a summary.

## How to present results

For a proactive card (heartbeat):
- Headline: Top 2-3 movers with arrows, e.g., "AAPL +2.1%, TSLA -0.8%"
- Body: Brief market context if notable (e.g., "Tech sector rallying")
- Action: Open the user's brokerage app (check installed apps for Robinhood, Fidelity, Schwab, E*Trade, Webull)

For a chat response:
- Full breakdown: each symbol with price, change, percent change
- Brief commentary on notable moves
- If the user asks about a specific stock, give more detail

## When to show proactively
- Morning through afternoon on weekdays (markets are open ~9:30 AM - 4 PM ET)
- Skip weekends and late night — markets are closed
- Only show if there are meaningful moves (>0.5% change on any position)
