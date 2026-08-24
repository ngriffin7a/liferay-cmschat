#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-local.sh – Build and run the cmschat microservice locally
#
# Usage:
#   ./run-local.sh              Build the bootJar and start the Spring Boot app
#   ./run-local.sh --skip-build Start from an already-built jar (faster restart)
#
# Environment variables can be supplied via a .env file in this directory.
# See .env.example for the expected keys.
#
# The microservice receives chat requests from the fragment (bearing a Liferay
# OAuth2 JWT), searches Liferay content via the Headless Search REST API on the
# user's behalf, and drives OpenAI to compose the response. It must point at the
# Liferay instance that ISSUES those JWTs -- the same instance whose JWKS
# validates them and whose Headless APIs it queries. Set LIFERAY_DXP_HOST (and
# optionally LIFERAY_BASE_URL) accordingly.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# ── Load .env file if present ───────────────────────────────────────────────
if [[ -f "${SCRIPT_DIR}/.env" ]]; then
    echo "📄 Loading environment from ${SCRIPT_DIR}/.env"
    set -a
    # shellcheck disable=SC1091
    source "${SCRIPT_DIR}/.env"
    set +a
fi

# ── Create LXC configtree directories ───────────────────────────────────────
#
# On Liferay Cloud (LCP), the Spring Boot client extension library reads
# properties from "configtree" directories mounted by the platform:
#
#   - /etc/liferay/lxc/dxp-metadata       → DXP connection properties
#   - /etc/liferay/lxc/ext-init-metadata  → client extension OAuth metadata
#
# Each file in these directories becomes a Spring property where the filename
# is the property key and the file content is the value. When running locally,
# these directories do not exist, so the script creates them in a temporary
# location and points the env vars accordingly.
#
# The DXP metadata must point at the Liferay instance that ISSUES the fragment
# JWTs (the same instance whose JWKS validates them, and whose Headless APIs the
# service queries).
# ---------------------------------------------------------------------------

LIFERAY_DXP_HOST="${LIFERAY_DXP_HOST:-localhost:9080}"
LIFERAY_DXP_PROTOCOL="${LIFERAY_DXP_PROTOCOL:-http}"

# Browser-facing origin host(s) for CORS -- comma/newline separated host[:port].
#
# The Liferay spring-boot util library (LiferayWebMvcConfigurer) derives the CORS
# allow-list from com.liferay.lxc.dxp.domains as {http,https}://<host> for each
# entry, and (at LOWEST_PRECEDENCE, mapping /**) overrides any allowedOriginPatterns
# the microservice declares. On a public-proxied demo the browser loads the portal
# from the PUBLIC host (e.g. ngriffin.lfr-demo.se), NOT localhost, so that public
# origin must appear here or the fragment's preflight is rejected with 403.
# Server-to-server calls (JWKS, client-id resolution) keep using mainDomain below.
LIFERAY_DXP_CORS_DOMAINS="${LIFERAY_DXP_CORS_DOMAINS:-${LIFERAY_DXP_HOST}}"

CONFIGTREE_DIR="${SCRIPT_DIR}/.configtree"
DXP_METADATA_DIR="${CONFIGTREE_DIR}/dxp-metadata"
EXT_METADATA_DIR="${CONFIGTREE_DIR}/ext-init-metadata"

mkdir -p "${DXP_METADATA_DIR}" "${EXT_METADATA_DIR}"

# DXP metadata – consumed by the spring-boot3 client-extension util library's
# OAuth2 resource-server security config (JWT validation).
printf '%s' "${LIFERAY_DXP_CORS_DOMAINS}" > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.domains"
printf '%s' "${LIFERAY_DXP_HOST}"         > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.mainDomain"
printf '%s' "${LIFERAY_DXP_PROTOCOL}"     > "${DXP_METADATA_DIR}/com.liferay.lxc.dxp.server.protocol"

echo "📂 Created LXC configtree at ${CONFIGTREE_DIR}"
echo "   DXP domain (server-to-server): ${LIFERAY_DXP_PROTOCOL}://${LIFERAY_DXP_HOST}"
echo "   CORS origins (browser-facing): ${LIFERAY_DXP_CORS_DOMAINS}"

# Point the env vars that application.properties references
export LIFERAY_ROUTES_DXP="${DXP_METADATA_DIR}"
export LIFERAY_ROUTES_CLIENT_EXTENSION="${EXT_METADATA_DIR}"

# Keep the Headless API connection (liferay.base.url) on the SAME Liferay
# instance that issues and validates the JWTs above. Otherwise the token is
# signed by one instance and checked against another's JWKS ("Invalid
# signature").
export LIFERAY_BASE_URL="${LIFERAY_BASE_URL:-${LIFERAY_DXP_PROTOCOL}://${LIFERAY_DXP_HOST}}"

# ── Resolve Gradle wrapper ──────────────────────────────────────────────────
GRADLEW="${PROJECT_ROOT}/gradlew"
if [[ ! -x "${GRADLEW}" ]]; then
    echo "❌ Gradle wrapper not found or not executable at ${GRADLEW}"
    exit 1
fi

# ── Build (unless --skip-build) ─────────────────────────────────────────────
SKIP_BUILD=false
for arg in "$@"; do
    [[ "${arg}" == "--skip-build" ]] && SKIP_BUILD=true
done

if [[ "${SKIP_BUILD}" == false ]]; then
    echo "🔨 Building cmschat-microservice bootJar …"
    "${GRADLEW}" -p "${PROJECT_ROOT}" :client-extensions:cmschat-microservice:bootJar
    echo ""
fi

# ── Locate the boot jar ─────────────────────────────────────────────────────
# Exclude the "-plain.jar" produced alongside the bootJar (the java-library
# plugin's plain jar is not an executable Spring Boot jar).
BUILD_DIR="${SCRIPT_DIR}/build/libs"
BOOT_JAR=$(find "${BUILD_DIR}" -maxdepth 1 -name "*.jar" ! -name "*-plain.jar" -type f 2>/dev/null | head -1)

if [[ -z "${BOOT_JAR}" ]]; then
    echo "❌ No jar found in ${BUILD_DIR}. Run without --skip-build first."
    exit 1
fi

echo ""
echo "🚀 Starting cmschat-microservice from: $(basename "${BOOT_JAR}")"
echo "   Port:  58081"
echo "   Ready: http://localhost:58081/ready"
echo ""

# ── Run the Spring Boot application ─────────────────────────────────────────
exec java \
    -XX:MaxRAMPercentage=50.0 \
    -Dspring.profiles.active=default \
    -jar "${BOOT_JAR}"
