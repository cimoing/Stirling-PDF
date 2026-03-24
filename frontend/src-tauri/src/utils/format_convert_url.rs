//! FormatConvert base URL selected only by **Rust compile configuration** (no env vars).
//!
//! - **Debug** (`tauri dev`, `cargo build`): `http://127.0.0.1:8080`
//! - **Release + feature `formatconvert-dev`**: `https://plexpdf-test.wenxstudio.ai`  
//!   (npm `tauri-build-dev` / `tauri-build-dev-{mac,windows,linux}` merge `tauri.desktop-dev.conf.json`, or `cargo --features formatconvert-dev`)
//! - **Release without that feature** (default `tauri build`): `https://plexpdf.wenxstudio.ai`

/// Base URL passed to the JVM as `-DformatConvert.baseUrl=...`.
pub fn format_convert_base_url() -> &'static str {
    #[cfg(debug_assertions)]
    {
        "http://127.0.0.1:8080"
    }
    #[cfg(all(not(debug_assertions), feature = "formatconvert-dev"))]
    {
        "https://plexpdf-test.wenxstudio.ai"
    }
    #[cfg(all(not(debug_assertions), not(feature = "formatconvert-dev")))]
    {
        "https://plexpdf.wenxstudio.ai"
    }
}
