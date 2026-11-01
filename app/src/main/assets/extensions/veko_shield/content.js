"use strict";

(function () {
  const href = window.location ? window.location.href : "";
  if (!href.startsWith("http://") && !href.startsWith("https://")) {
    return;
  }

  const TRACKING_PARAMS = new Set([
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "utm_name", "fbclid", "gclid", "dclid", "gbraid", "wbraid",
    "msclkid", "twclid", "ttclid", "li_fat_id", "igshid", "igs",
    "mc_cid", "mc_eid", "_hsenc", "_hsmi", "yclid", "_openstat",
    "vero_id", "mkt_tok", "_ga", "_gl", "spm", "scm"
  ]);

  function cleanLocationTrackingParams() {
    try {
      if (!window.location.search || window.location.search.length <= 1) return;
      const parsed = new URL(window.location.href);
      let removed = false;
      for (const key of Array.from(parsed.searchParams.keys())) {
        if (TRACKING_PARAMS.has(key.toLowerCase())) {
          parsed.searchParams.delete(key);
          removed = true;
        }
      }
      if (removed && window.history && typeof window.history.replaceState === "function") {
        window.history.replaceState(window.history.state, "", parsed.toString());
      }
    } catch (_) {
    }
  }

  function injectCosmeticCss(cssText) {
    if (!cssText || typeof cssText !== "string") return;
    const apply = () => {
      const root = document.head || document.documentElement;
      if (!root) return false;
      if (root.querySelector("style[data-veko-shield='1']")) return true;
      const style = document.createElement("style");
      style.setAttribute("data-veko-shield", "1");
      style.textContent = cssText;
      root.appendChild(style);
      return true;
    };
    if (!apply()) {
      document.addEventListener("DOMContentLoaded", apply, { once: true });
    }
  }

  function applyPageAntiTrackingGuards(strictMode) {
    try {
      const pageScript = document.createElement("script");
      pageScript.textContent = `(function(){
        try {
          if (!('globalPrivacyControl' in navigator) || navigator.globalPrivacyControl !== true) {
            Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', {
              get: function() { return true; },
              configurable: true
            });
          }
          if (${strictMode ? "true" : "false"}) {
            if ('getBattery' in Navigator.prototype) {
              Object.defineProperty(Navigator.prototype, 'getBattery', {
                value: undefined,
                configurable: true
              });
            }
            if ('deviceMemory' in Navigator.prototype) {
              Object.defineProperty(Navigator.prototype, 'deviceMemory', {
                get: function() { return 8; },
                configurable: true
              });
            }
            if ('hardwareConcurrency' in Navigator.prototype) {
              Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', {
                get: function() { return 4; },
                configurable: true
              });
            }
          }
        } catch (_) {}
      })();`;
      const target = document.documentElement || document.head;
      if (target) {
        target.appendChild(pageScript);
        pageScript.remove();
      }
    } catch (_) {
    }

    document.addEventListener(
      "click",
      (ev) => {
        try {
          const anchor = ev.target && ev.target.closest ? ev.target.closest("a[ping]") : null;
          if (anchor && anchor.hasAttribute("ping")) {
            anchor.removeAttribute("ping");
          }
        } catch (_) {
        }
      },
      true
    );
  }

  browser.runtime
    .sendMessage({ type: "requestPageShield", url: href })
    .then((cfg) => {
      if (!cfg || cfg.paused === true) {
        return;
      }
      if (cfg.stripParams === true) {
        cleanLocationTrackingParams();
      }
      if (typeof cfg.cosmeticCss === "string" && cfg.cosmeticCss.length > 0) {
        injectCosmeticCss(cfg.cosmeticCss);
      }
      if (cfg.antiTracking === true) {
        applyPageAntiTrackingGuards(Boolean(cfg.strictAntiTracking));
      }
    })
    .catch(() => {});
})();
