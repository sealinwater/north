version = 32

cloudstream {
    authors = listOf("North • sealinwater")
    description = "⚠️ ACCOUNT API KEY REQUIRED: North requires a NordicBytes (nordicbytes.org) account & API Key to work. HOW TO SIGN UP & GET A KEY: 1) Apply at https://nordicbytes.org/application — your application text & experience level are public for community vouching (NEVER include your real name, address, or personal info!), while your email and at least 2 tracker proof screenshots + profile URLs are staff-only (required; no proof = rejection). 2) Once logged in at nordicbytes.org, go to Profile -> Settings -> API Key and copy your UNIT3D API Token. 3) Open Cloudstream -> Settings -> Extensions -> North (Gear Icon), paste your key, and Save. Features NORTH Studio Intro, Companion Proxy API, Seekable MKV/MP4 Streaming, Chromecast Stereo AAC Transcoding, TheIntroDB Skip Buttons & Ghost Ratio Protection."
    iconUrl = "https://raw.githubusercontent.com/sealinwater/north/builds/icon.png"
    language = "da"
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Documentary")
    status = 1
}

android {
    buildFeatures {
        buildConfig = true
    }
}
