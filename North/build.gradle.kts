version = 49

cloudstream {
    authors = listOf("North • sealinwater")
    description = "⚠️ ACCOUNT API KEY REQUIRED: You need a NordicBytes (nordicbytes.org) account and API key to use North. How to get set up: 1) Apply at https://nordicbytes.org/application. Everything you write in your application text and experience level is public so members can vouch for you, so never put your real name, address, or personal info in there. Your email and at least 2 tracker proof screenshots + profile links are only shown to staff (you do need proofs or the application gets rejected). 2) Once you can log in at nordicbytes.org, go to Profile -> Settings -> API Key and copy your UNIT3D API token. 3) In Cloudstream, go to Settings -> Extensions -> North (gear icon), paste your key, and hit Save. Includes the Nordic Aurora intro, seekable MKV/MP4 playback, Chromecast stereo audio support, skip intro buttons, and Ghost Ratio protection."
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
