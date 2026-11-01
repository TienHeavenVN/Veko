"use strict";

(function () {
  const NATIVE_APP = "vekoShield";
  const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]"]);

  function extractHostname(rawUrl) {
    try {
      return new URL(rawUrl).hostname.toLowerCase();
    } catch (_) {
      return "";
    }
  }

  function shouldBypassFast(details) {
    const reqUrl = details.url || "";
    if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) {
      return true;
    }
    const reqHost = extractHostname(reqUrl);
    if (!reqHost) {
      return true;
    }
    if (LOOPBACK_HOSTS.has(reqHost) && !reqUrl.includes("/ads/") && !reqUrl.includes("utm_")) {
      return true;
    }
    const originHost = extractHostname(details.documentUrl || details.originUrl || "");
    if (
      originHost &&
      reqHost === originHost &&
      !reqUrl.includes("?") &&
      (details.type === "imageset" || details.type === "image" || details.type === "font" || details.type === "stylesheet") &&
      !reqUrl.includes("/ads/") &&
      !reqUrl.includes("/pagead/") &&
      !reqUrl.includes("/adserver/")
    ) {
      return true;
    }
    return false;
  }

  function queryNativeWithTimeout(payload, timeoutMs) {
    return new Promise((resolve) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          resolve(null);
        }
      }, timeoutMs);

      browser.runtime
        .sendNativeMessage(NATIVE_APP, payload)
        .then((res) => {
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            resolve(res || null);
          }
        })
        .catch(() => {
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            resolve(null);
          }
        });
    });
  }

  browser.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (details.type === "main_frame" || shouldBypassFast(details)) {
        return {};
      }
      return queryNativeWithTimeout(
        {
          type: "evaluateSubresource",
          url: details.url || "",
          topFrameUrl: details.documentUrl || details.originUrl || "",
          resourceType: details.type || "other",
          method: details.method || "GET",
          incognito: Boolean(details.incognito),
        },
        450
      ).then((verdict) => {
        if (!verdict) {
          return {};
        }
        if (verdict.cancel === true) {
          return { cancel: true };
        }
        if (typeof verdict.redirectUrl === "string" && verdict.redirectUrl.length > 0 && verdict.redirectUrl !== details.url) {
          return { redirectUrl: verdict.redirectUrl };
        }
        return {};
      });
    },
    {
      urls: ["http://*/*", "https://*/*"],
      types: [
        "script",
        "xmlhttprequest",
        "beacon",
        "ping",
        "sub_frame",
        "image",
        "imageset",
        "media",
        "websocket",
        "csp_report",
        "other"
      ]
    },
    ["blocking"]
  );

  browser.runtime.onMessage.addListener((msg, sender) => {
    if (!msg || msg.type !== "requestPageShield") {
      return Promise.resolve(null);
    }
    const incognito = Boolean(sender && sender.tab && sender.tab.incognito);
    return queryNativeWithTimeout(
      {
        type: "pageShield",
        url: msg.url || "",
        incognito: incognito,
      },
      600
    );
  });
})();
