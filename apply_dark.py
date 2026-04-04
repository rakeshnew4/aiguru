#!/usr/bin/env python3
"""Apply dark theme color substitutions to all 11 restored layout files.
Restores from /tmp/orig_activity_*.xml and /tmp/orig_item_*.xml first, then applies dark theme.
"""
import shutil, os

# Comprehensive color mapping: (attribute_name_contains, old_value, new_value)
# Processed in order; first match wins for each token.
REPLACEMENTS = [
    # Backgrounds: root screens
    ('android:background="#EEF2FF"', 'android:background="#0D1117"'),
    ('android:background="#F0F4F8"', 'android:background="#0D1117"'),
    ('android:background="#F0F8FF"', 'android:background="#0D1117"'),
    # Backgrounds: header bars
    ('android:background="#1565C0"', 'android:background="#0F1724"'),
    ('android:background="#1A73E8"', 'android:background="#0F1724"'),
    ('android:background="#3949AB"', 'android:background="#0F1724"'),
    # Backgrounds: card surfaces
    ('android:background="#FFFFFF"', 'android:background="#161D2A"'),
    ('android:background="#EAF3FF"', 'android:background="#161D2A"'),
    ('android:background="#E3F2FD"', 'android:background="#1A2233"'),
    ('android:background="#E0E0E0"', 'android:background="#FFFFFF18"'),
    ('android:background="#E8EAED"', 'android:background="#FFFFFF18"'),
    ('android:background="#F5F5F5"', 'android:background="#1A2233"'),
    ('android:background="#ECEFF1"', 'android:background="#1A2233"'),
    ('android:background="#F1F5F9"', 'android:background="#1A2233"'),
    ('android:background="#FFF3E0"', 'android:background="#1A2000"'),
    # Card backgrounds
    ('app:cardBackgroundColor="#FFFFFF"', 'app:cardBackgroundColor="#161D2A"'),
    # Primary text (dark → white)
    ('android:textColor="#0E3A72"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#1A237E"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#111827"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#212121"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#333333"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#1F2937"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#263238"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#37474F"', 'android:textColor="#FFFFFF"'),
    ('android:textColor="#424242"', 'android:textColor="#FFFFFF"'),
    # Secondary text (gray → #8899AA)
    ('android:textColor="#374151"', 'android:textColor="#8899AA"'),
    ('android:textColor="#546E7A"', 'android:textColor="#8899AA"'),
    ('android:textColor="#6B7280"', 'android:textColor="#8899AA"'),
    ('android:textColor="#666666"', 'android:textColor="#8899AA"'),
    ('android:textColor="#78909C"', 'android:textColor="#8899AA"'),
    ('android:textColor="#90A4AE"', 'android:textColor="#8899AA"'),
    ('android:textColor="#9CA3AE"', 'android:textColor="#8899AA"'),
    ('android:textColor="#607D8B"', 'android:textColor="#8899AA"'),
    ('android:textColor="#B3C5FF"', 'android:textColor="#8899AA"'),
    ('android:textColor="#B3D1F7"', 'android:textColor="#8899AA"'),
    # Accent text (blue → cyan)
    ('android:textColor="#1565C0"', 'android:textColor="#40E0D0"'),
    ('android:textColor="#1A73E8"', 'android:textColor="#40E0D0"'),
    ('android:textColor="#3949AB"', 'android:textColor="#40E0D0"'),
    # Warning/orange text → gold
    ('android:textColor="#E65100"', 'android:textColor="#FFE040"'),
    ('android:textColor="#FB8C00"', 'android:textColor="#FFE040"'),
    ('android:textColor="#FF8F00"', 'android:textColor="#FFE040"'),
    ('android:textColor="#BF360C"', 'android:textColor="#8899AA"'),
    # Green text (status) → light green
    ('android:textColor="#2E7D32"', 'android:textColor="#4ADE80"'),
    # Error/red text
    ('android:textColor="#C62828"', 'android:textColor="#FF6B6B"'),
    # Hint text
    ('android:textColorHint="#9CA3AF"', 'android:textColorHint="#8899AA"'),
    ('android:textColorHint="#9CA3AE"', 'android:textColorHint="#8899AA"'),
    ('android:textColorHint="#B0BEC5"', 'android:textColorHint="#8899AA"'),
    ('android:textColorHint="#78909C"', 'android:textColorHint="#8899AA"'),
    ('android:textColorHint="#607D8B"', 'android:textColorHint="#8899AA"'),
    ('android:textColorHint="#666666"', 'android:textColorHint="#8899AA"'),
    # Button tints (blue → cyan)
    ('app:backgroundTint="#1565C0"', 'app:backgroundTint="#40E0D0"'),
    ('app:backgroundTint="#1A73E8"', 'app:backgroundTint="#40E0D0"'),
    ('app:backgroundTint="#2196F3"', 'app:backgroundTint="#40E0D0"'),
    ('app:backgroundTint="#0288D1"', 'app:backgroundTint="#40E0D0"'),
    ('app:backgroundTint="#3949AB"', 'app:backgroundTint="#40E0D0"'),
    # Progress tints
    ('android:progressTint="#1565C0"', 'android:progressTint="#40E0D0"'),
    ('android:progressTint="#2196F3"', 'android:progressTint="#40E0D0"'),
    ('android:indeterminateTint="#1565C0"', 'android:indeterminateTint="#40E0D0"'),
    # Icon tints
    ('app:tint="#1565C0"', 'app:tint="#40E0D0"'),
    # Stroke/box colors
    ('app:strokeColor="#1565C0"', 'app:strokeColor="#40E0D0"'),
    ('app:boxStrokeColor="#1565C0"', 'app:boxStrokeColor="#40E0D0"'),
]

# Button text that should be dark when button background is cyan
BUTTON_TEXT_FIX = [
    ('app:backgroundTint="#40E0D0"',),  # marker
]

files = [
    ('orig_item_chapter_page', 'item_chapter_page'),
    ('orig_item_library_book', 'item_library_book'),
    ('orig_item_plan_card', 'item_plan_card'),
    ('orig_activity_email_auth', 'activity_email_auth'),
    ('orig_activity_library', 'activity_library'),
    ('orig_activity_page_viewer', 'activity_page_viewer'),
    ('orig_activity_subscription', 'activity_subscription'),
    ('orig_activity_chapter', 'activity_chapter'),
    ('orig_activity_progress_dashboard', 'activity_progress_dashboard'),
    ('orig_activity_revision', 'activity_revision'),
    ('orig_activity_user_profile', 'activity_user_profile'),
]

for (orig_name, name) in files:
    src = f'/tmp/{orig_name}.xml'
    dst = f'app/src/main/res/layout/{name}.xml'
    
    # Restore original
    if os.path.exists(src):
        shutil.copy(src, dst)
    
    with open(dst, encoding='utf-8') as f:
        content = f.read()
    
    new = content
    for old, rep in REPLACEMENTS:
        new = new.replace(old, rep)
    
    with open(dst, 'w', encoding='utf-8') as f:
        f.write(new)
    
    changes = sum(1 for (o, r) in REPLACEMENTS if o in content)
    print(f'Done ({changes} patterns): {name}')

print('All done')

