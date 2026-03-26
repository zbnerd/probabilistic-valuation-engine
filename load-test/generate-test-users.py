#!/usr/bin/env python3
"""
Generate random test user IGNS for load testing
"""

import random
import string

def generate_random_userign(count=1000):
    """Generate random user IGNS in Korean MapleStory format"""
    user_igns = []

    for _ in range(count):
        # Generate random IGN: 4-16 characters, mixed Korean + English
        length = random.randint(4, 12)

        # Mix of Korean characters and English
        ign = ""
        for _ in range(length):
            choice = random.choice(['korean', 'english', 'number'])
            if choice == 'korean':
                # Korean syllable range (가-힣)
                ign += chr(random.randint(0xAC00, 0xD7A3))
            elif choice == 'english':
                ign += random.choice(string.ascii_letters)
            else:
                ign += random.choice(string.digits)

        user_igns.append(ign)

    return user_igns

if __name__ == '__main__':
    import sys

    count = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
    output_file = sys.argv[2] if len(sys.argv) > 2 else "/tmp/random_1000_userigns.txt"

    print(f"Generating {count} random user IGNS...")
    user_igns = generate_random_userign(count)

    with open(output_file, 'w', encoding='utf-8') as f:
        for ign in user_igns:
            f.write(f"{ign}\n")

    print(f"✅ Generated {count} random user IGNS: {output_file}")
    print(f"Sample: {user_igns[:5]}")
