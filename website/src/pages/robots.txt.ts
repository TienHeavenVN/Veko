import type { APIRoute } from 'astro';
import { VEKO_SITE_ORIGIN } from '../data/vekoData';
import { withBase } from '../utils/url';

export const GET: APIRoute = ({ site }) => {
  const origin = site ?? new URL(VEKO_SITE_ORIGIN);
  const sitemapUrl = new URL(withBase('/sitemap-index.xml'), origin).toString();
  const body = `User-agent: *\nAllow: /\n\nSitemap: ${sitemapUrl}\n`;

  return new Response(body, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  });
};
