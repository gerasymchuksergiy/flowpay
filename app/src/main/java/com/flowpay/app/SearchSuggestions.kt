package com.flowpay.app

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Google's Search Suggestions, drawn as Google sent them.
 *
 * The Gemini terms allow a grounded answer to be shown only together with its
 * Search Suggestions, unaltered — same colours, same fonts — and the HTML for them
 * arrives with the answer. The app has no WebView anywhere else, which is why this
 * was the one open compliance item in HANDOFF §14; the platform WebView needs no
 * dependency, so this is the smallest thing that satisfies it.
 *
 * JavaScript stays off: the chips are links styled with CSS and need none. A tap
 * leaves the WebView and opens the search in the phone's browser, which the terms
 * name as an acceptable place for it. The background is transparent so the chips
 * sit on the card rather than in a white box; the theme is dark, so the WebView
 * applies the content's own dark styles.
 *
 * Nothing on any phone has rendered this yet. It is built to the documented
 * contract and has not been seen.
 */
@Composable
fun SearchSuggestions(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openLink(view.context, request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    )
}
