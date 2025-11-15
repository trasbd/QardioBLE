#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ring_capture.py — Connect to SY01 smart ring, enable notifications, and log output.

This version:
 • Connects directly by MAC
 • Discovers all services
 • Subscribes to all characteristics that support notify/indicate (including FF01 and B003)
 • Does NOT send any speculative commands
 • Saves all notifications to ./captures/<mac>_<timestamp>.csv
"""

import asyncio
import os
import csv
from datetime import datetime
from bleak import BleakClient

# On Windows/Linux, you can hardcode the MAC address.
# On macOS, BLE uses UUIDs, not MACs, so we scan by device name instead.
DEVICE_NAME = "SY01"
USE_SCAN = True  # automatically detect on macOS

# Change this to your ring's MAC address
RING_MAC = "30:33:00:00:31:3F"
CAPTURE_DIR = "captures"

# Known interesting UUIDs (for logging emphasis only)
UUID_FF01 = "0000ff01-0000-1000-8000-00805f9b34fb"
UUID_B003 = "0000b003-0000-1000-8000-00805f9b34fb"


def hex_dump(data: bytes) -> str:
    return "-".join(f"{b:02X}" for b in data)


async def resolve_device():
    from bleak import BleakScanner
    if not USE_SCAN:
        return RING_MAC
    print("🔍 Scanning for SY01...")
    device = await BleakScanner.find_device_by_filter(lambda d, ad: d.name and DEVICE_NAME in d.name)
    if not device:
        raise Exception("❌ Could not find SY01 nearby.")
    print(f"✅ Found {device.name}: {device.address}")
    return device


async def run():
    print(f"🔗 Connecting to SY01 at {RING_MAC} …")

    os.makedirs(CAPTURE_DIR, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    base = os.path.join(CAPTURE_DIR, f"{RING_MAC.replace(':', '')}_{ts}")
    csv_path = base + ".csv"

    device = await resolve_device()
    async with BleakClient(device, timeout=30.0) as client:

        if not client.is_connected:
            print("❌ Connection failed.")
            return
        # mtu = await client.get_mtu()
        # print("Negotiated MTU:", mtu)

        print("✅ Connected, discovering services…")

        # Handle Bleak version differences
        try:
            services = client.services or await client.get_services()
        except AttributeError:
            services = await client.get_services()

        if hasattr(services, "services"):
            service_list = list(services.services.values())
        else:
            service_list = list(services)

        print(f"📋 Discovered {len(service_list)} services:")
        for svc in service_list:
            print(f"  • {svc.uuid}")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                print(f"     - {ch.uuid}  ({props})")

        # Find all notify/indicate characteristics actually present
        notify_chars = [
            ch
            for svc in service_list
            for ch in svc.characteristics
            if any(p in ("notify", "indicate") for p in ch.properties)
        ]

        if not notify_chars:
            print("⚠️ No notify/indicate characteristics found; nothing to subscribe to.")
        else:
            print("\n🔔 Will enable notifications on:")
            for ch in notify_chars:
                extra = ""
                if ch.uuid.lower() == UUID_B003:
                    extra = "  <-- main data dump (b003)"
                elif ch.uuid.lower() == UUID_FF01:
                    extra = "  <-- ff01"
                print(f"  - {ch.uuid}{extra}")

        csvfile = open(csv_path, "w", newline="", encoding="utf-8")
        writer = csv.writer(csvfile)
        writer.writerow(["timestamp_utc", "char_uuid", "len", "data_hex"])

        def handle(sender, data: bytes):
            ts = datetime.utcnow().isoformat(timespec="milliseconds") + "Z"
            h = hex_dump(data)
            print(f"[{ts}] {sender}: {h}")
            writer.writerow([ts, sender, len(data), h])
            csvfile.flush()

        # Subscribe to each notify/indicate characteristic
        for ch in notify_chars:
            try:
                await client.start_notify(ch.uuid, handle)
                print(f"✅ Subscribed to {ch.uuid}")
                await asyncio.sleep(0.05)
            except Exception as e:
                print(f"⚠️ Could not enable {ch.uuid}: {e}")

        print("\n📡 Waiting for ring to send data (Ctrl+C to stop)…")
        try:
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            print("\n⏹ Stopping notifications…")
            for ch in notify_chars:
                try:
                    await client.stop_notify(ch.uuid)
                except Exception:
                    pass
            csvfile.close()

    print(f"✅ Capture saved to {csv_path}")


if __name__ == "__main__":
    asyncio.run(run())
