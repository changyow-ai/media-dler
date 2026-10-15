(function (shop, product) {
  'use strict';
  const u = new URL(location.href);
  const documentMatches = u.protocol === 'https:' &&
    (u.hostname === 'shopee.tw' || u.hostname === 'www.shopee.tw') &&
    u.pathname.replace(/\/$/, '') === '/product/' + shop + '/' + product;
  const base = {documentUrl: u.origin + u.pathname, documentMatches, found: false};
  if (!documentMatches) return JSON.stringify(base);
  for (const node of document.querySelectorAll('script[type="text/mfe-initial-data"]')) {
    if (!node.textContent || node.textContent.length > 8000000) continue;
    let root;
    try { root = JSON.parse(node.textContent); } catch (_) { continue; }
    const state = root && root.initialState;
    if (!state) continue;
    const cache = state.DOMAIN_PDP && state.DOMAIN_PDP.data &&
      state.DOMAIN_PDP.data.PDP_BFF_DATA && state.DOMAIN_PDP.data.PDP_BFF_DATA.cachedMap;
    const cachedItem = cache && cache[shop + '/' + product] && cache[shop + '/' + product].item;
    const storedItem = state.item && state.item.items && state.item.items[product];
    for (const item of [cachedItem, storedItem]) {
      if (!item || String(item.item_id) !== product || String(item.shop_id) !== shop) continue;
      if (!Array.isArray(item.images) || !Array.isArray(item.video_info_list)) continue;
      const rawImages = item.images;
      const images = rawImages.filter(x => typeof x === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(x));
      const rawVideos = item.video_info_list;
      const videos = rawVideos.slice(0, 8).map((video, i) => {
        let mms = {};
        if (typeof video.mms_data === 'string' && video.mms_data.length <= 100000) {
          try { mms = JSON.parse(video.mms_data); } catch (_) {}
        }
        const choices = [].concat(Array.isArray(video.formats) ? video.formats : [],
          video.default_format || [], Array.isArray(mms.formats) ? mms.formats : [], mms.default_format || []);
        const formats = [];
        for (const f of choices.slice(0, 64)) {
          if (!f || typeof f.url !== 'string' || f.url.length > 4096) continue;
          try {
            const media = new URL(f.url);
            if (media.protocol !== 'https:' || !media.hostname.endsWith('.vod.susercontent.com') ||
              !/\.mp4$/i.test(media.pathname) || media.username || media.password ||
              (media.port && media.port !== '443')) continue;
            if (formats.some(x => x.url === media.href)) continue;
            formats.push({url: media.href, width: Number(f.width) || 0, height: Number(f.height) || 0,
              size: Number(f.size) || 0, codec: String(f.codec || ''), format: String(f.format || '')});
          } catch (_) {}
        }
        return {id: String(video.vid || mms.vid || i + 1),
          durationSeconds: Math.round(Number(video.duration) || Number(mms.duration) / 1000 || 0),
          thumbnail: typeof video.thumb_url === 'string' ? video.thumb_url : null, formats};
      });
      return JSON.stringify({...base, found: true, shopId: String(item.shop_id), itemId: String(item.item_id),
        gallerySourceCount: rawImages.length, galleryComplete: Array.isArray(item.images) &&
          rawImages.length <= 50 && images.length === rawImages.length,
        imageHashes: images, itemHasVideo: !!item.item_has_video,
        videoSourceCount: rawVideos.length, videos});
    }
  }
  return JSON.stringify(base);
})(__SHOP_ID_JSON__, __ITEM_ID_JSON__);
