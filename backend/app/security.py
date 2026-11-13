"""Network security, Tailscale subnet validation, and loopback boundary checks."""

from __future__ import annotations

import ipaddress
import secrets
from fastapi import Request

from app.errors import AssistantError

# Tailscale CGNAT subnets (RFC 6598 IPv4 and Tailscale ULA IPv6)
TAILSCALE_IPV4 = ipaddress.ip_network("100.64.0.0/10")
TAILSCALE_IPV6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def is_tailscale_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """Check if the IP address belongs to the Tailscale CGNAT or ULA subnet."""
    if isinstance(ip, ipaddress.IPv4Address):
        return ip in TAILSCALE_IPV4
    return ip in TAILSCALE_IPV6


def is_loopback_or_private(host: str) -> bool:
    """Check if the client host is loopback (127.0.0.1 / ::1), testclient, private LAN, or Tailscale."""
    if host in ("testclient", "localhost"):
        return True
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_loopback or ip.is_private or is_tailscale_ip(ip)
    except ValueError:
        return False


def verify_bearer_token(token: str | None, expected: str | None) -> bool:
    """Constant-time token verification."""
    if not expected:
        return True
    if not token:
        return False
    return secrets.compare_digest(token, expected)


def require_loopback(request: Request, error_message: str = "This service is available only through the local computer connection.") -> None:
    """Strictly assert that request originates from loopback (127.0.0.1 or testclient)."""
    host = request.client.host if request.client is not None else ""
    try:
        local = ipaddress.ip_address(host).is_loopback
    except ValueError:
        local = host in ("testclient", "localhost")
    if not local:
        raise AssistantError(403, "local_only", error_message)


def require_loopback_or_private_subnet(request: Request, error_message: str = "This service is available only on private local subnets.") -> None:
    """Assert that request originates from loopback or a private subnet (Wi-Fi Hotspot, LAN, or Tailscale)."""
    host = request.client.host if request.client is not None else ""
    if not is_loopback_or_private(host):
        raise AssistantError(403, "local_only", error_message)
