#!/usr/bin/env python3
"""
Extract random sample of user IGNS from existing CSV file
"""

import random
import sys
from pathlib import Path

def extract_random_users(csv_file, sample_size=1000, output_file="/tmp/sample_1000_userigns.txt"):
    """Extract random sample of user IGNS from CSV file"""

    # Read all user IGNS from CSV
    user_igns = []
    with open(csv_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                # Handle CSV format - extract first column (userIgn)
                # Assuming format: userIgn or userIgn,other_columns
                parts = line.split(',')
                user_ign = parts[0].strip().strip('"')  # Remove quotes if present
                if user_ign and user_ign != 'userIgn':  # Skip header
                    user_igns.append(user_ign)

    print(f"📊 Total user IGNS in CSV: {len(user_igns)}")

    # Random sample
    if len(user_igns) < sample_size:
        print(f"⚠️  Warning: CSV has only {len(user_igns)} entries, using all")
        sample_size = len(user_igns)

    sample = random.sample(user_igns, sample_size)

    # Write to output file
    with open(output_file, 'w', encoding='utf-8') as f:
        for ign in sample:
            f.write(f"{ign}\n")

    print(f"✅ Extracted {len(sample)} random user IGNS: {output_file}")
    print(f"Sample: {sample[:5]}")

    return sample

if __name__ == '__main__':
    csv_file = sys.argv[1] if len(sys.argv) > 1 else "/home/maple/probabilistic-valuation-engine/module-app/src/main/resources/data/userIgn_List.csv"
    sample_size = int(sys.argv[2]) if len(sys.argv) > 2 else 1000
    output_file = sys.argv[3] if len(sys.argv) > 3 else "/tmp/sample_1000_userigns.txt"

    extract_random_users(csv_file, sample_size, output_file)
