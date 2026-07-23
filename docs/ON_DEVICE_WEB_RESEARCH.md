# On-device web research

The Android app performs page crawling and text extraction on the phone. It has no Crawl4AI server,
hosted crawler, API token, or research-service configuration.

## Research flow

1. Direct HTTPS links in the question are used as-is. For general questions, pluggable search
   providers discover candidate public URLs. DuckDuckGo HTML and Wikipedia are the defaults.
2. OkHttp downloads up to four pages concurrently. A public-only DNS policy rejects loopback,
   link-local, and private-network addresses, including redirect targets.
3. Jsoup removes navigation, scripts, forms, ads, cookie banners, and other low-value elements. It
   extracts headings, paragraphs, lists, tables, quotations, and code from the most likely article
   root.
4. If the static response contains too little readable content, an isolated WebView renders the
   HTTPS page and returns its visible text. The WebView has no native bridge or local-file access;
   it blocks mixed content, images, non-HTTPS resources, popups, and invalid TLS.
5. Query terms rank the extracted paragraphs locally. Only bounded, relevant excerpts and their
   source URLs enter the on-device model prompt.
6. Qwen synthesizes the extracted excerpts into the answer. A deterministic grounding check
   replaces citation-only output with a concise extracted-page digest.
7. The final answer uses ordinary Markdown links, rendered by the app as citation pills.

## Cache and connectivity

Discovery results and extracted public text are cached in the app cache directory for 30 minutes.
The cache is capped at 64 files and may be cleared by Android at any time. No note content or chat
history is written to the research cache.

Search providers can still throttle automated discovery because a phone does not contain a global
web index. Direct links and already-cached research do not require a search request. Discovery is
represented by `WebSearchProvider`, allowing another compliant provider to be added without
changing crawling or extraction.

If Android reports no validated connection, research-required questions show a connectivity alert.
Requests explicitly grounded in `/note`, or answerable from the on-device model without note
evidence, continue without web access.

## Security boundary

Research accepts HTTPS URLs only. Cleartext traffic is disabled app-wide. Both literal private URLs
and DNS answers resolving to local/private addresses are rejected by the direct downloader. The
WebView fallback never exposes an `addJavascriptInterface` bridge and cannot access app files or
content providers.
