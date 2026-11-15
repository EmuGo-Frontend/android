#!/bin/sh
set -eu
archive=${1:?Usage: scripts/prepare-discord-sdk.sh /path/to/DiscordSocialSdk.zip}
mkdir -p .local-sdk
unzip -p "$archive" discord_social_sdk/lib/release/discord_partner_sdk.aar > .local-sdk/discord_partner_sdk.aar
unzip -p "$archive" discord_social_sdk/include/discordpp.h > .local-sdk/discordpp.h
unzip -p "$archive" discord_social_sdk/include/cdiscord.h > .local-sdk/cdiscord.h
