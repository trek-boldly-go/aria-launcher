---
name: weather-destination
description: Check weather for any city or travel destination. Use when planning trips, packing, or the user asks about weather somewhere other than their current location.
metadata:
  author: aria
  version: "1.0"
  aria-trigger: on-demand
---

# Destination Weather

Check weather conditions for any city using the free Open-Meteo API (no API key needed).

## Step 1: Get coordinates
Fetch the city's latitude and longitude:
`https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=1&language=en&format=json`

Extract `results[0].latitude` and `results[0].longitude`. If no results, tell the user the city wasn't found.

## Step 2: Get weather
Fetch the forecast using the coordinates:
`https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=3`

## Step 3: Present results
For a chat response, include:
- Current conditions (temperature, feels like, humidity, wind)
- 3-day forecast with highs, lows, and rain chance
- Packing advice if the user mentioned travel (e.g., "Bring a jacket, highs only in the 50s")

For a proactive card:
- Headline: "{City}: {temp}F, {condition}"
- Body: "H:{high} L:{low}, {rain_chance}% rain"

## Weather codes
0=Clear, 1=Mostly clear, 2=Partly cloudy, 3=Overcast, 45/48=Foggy,
51-57=Drizzle, 61-67=Rain, 71-77=Snow, 80-82=Showers, 95-99=Thunderstorm
