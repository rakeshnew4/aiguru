#!/bin/bash
# Apply nginx security hardening for AI Guru
# Requires: sudo privileges, nginx installed

set -e

echo "🔒 Applying nginx security hardening..."

# Step 1: Uncomment server_tokens off
echo "  [1/4] Disabling server_tokens (nginx version hiding)..."
if sudo sed -i 's/^\s*#\s*server_tokens off;/\tserver_tokens off;/' /etc/nginx/nginx.conf; then
  echo "      ✓ Done"
else
  echo "      ✗ Failed"
  exit 1
fi

# Step 2: Update TLS protocols
echo "  [2/4] Restricting TLS to 1.2+ (removing deprecated TLS 1.0/1.1)..."
if sudo sed -i 's/ssl_protocols.*/ssl_protocols TLSv1.2 TLSv1.3;/' /etc/nginx/nginx.conf; then
  echo "      ✓ Done"
else
  echo "      ✗ Failed"
  exit 1
fi

# Step 3: Add security headers to site config
echo "  [3/4] Adding security headers to vkpremium site config..."
SITE_CONFIG="/etc/nginx/sites-enabled/vkpremium"
HEADERS_BLOCK='
    # Security headers
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains; preload" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;'

# Check if headers already present
if sudo grep -q "X-Frame-Options" "$SITE_CONFIG"; then
  echo "      ⚠ Headers already present, skipping..."
else
  # Add headers before the location / block in the HTTPS server block
  if sudo sed -i "/listen 443 ssl/,/location \/ {/s|proxy_send_timeout.*|&$HEADERS_BLOCK|" "$SITE_CONFIG"; then
    echo "      ✓ Done"
  else
    echo "      ✗ Failed - manual edit required"
    echo "      Add the following lines to $SITE_CONFIG after proxy_send_timeout:"
    echo "$HEADERS_BLOCK"
  fi
fi

# Step 4: Test and reload
echo "  [4/4] Testing nginx syntax..."
if sudo nginx -t; then
  echo "      ✓ Syntax OK"
  echo ""
  echo "  Reloading nginx..."
  if sudo systemctl reload nginx; then
    echo "      ✓ Reloaded"
  else
    echo "      ✗ Reload failed"
    exit 1
  fi
else
  echo "      ✗ Syntax error detected"
  exit 1
fi

echo ""
echo "✅ Security hardening complete!"
echo ""
echo "Verification:"
echo "  curl -I https://vkpremium.art 2>/dev/null | grep -i 'x-frame\|x-content\|strict'"
echo ""
echo "Expected output:"
echo "  x-frame-options: DENY"
echo "  x-content-type-options: nosniff"
echo "  strict-transport-security: max-age=31536000; includeSubDomains; preload"
