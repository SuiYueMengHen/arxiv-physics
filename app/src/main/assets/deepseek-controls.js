(() => {
  const action = __ARXIV_ACTION__;
  const textarea = document.querySelector('textarea');
  const visible = el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden'; };
  const enabled = el => !el.disabled && el.getAttribute('aria-disabled') !== 'true' && !el.classList.contains('ds-icon-button--disabled') && getComputedStyle(el).pointerEvents !== 'none';
  const labelValues = el => [el.getAttribute('aria-label'), el.getAttribute('title'), el.getAttribute('data-tooltip'), el.innerText].filter(Boolean).map(s => s.trim());
  const labels = el => labelValues(el).join(' ');
  const buttons = [...document.querySelectorAll('button,[role="button"],.ds-icon-button')].filter(visible);
  let target = null;
  if (action === 'upload') {
    const input = [...document.querySelectorAll('input[type="file"]')].find(el => !el.accept || /pdf|\*\/\*/i.test(el.accept));
    target = buttons.find(b => /上传文件|添加附件|上传附件|附件|Attach|Upload|添加文件/i.test(labels(b)));
    if (!target && input) {
      // Run the hidden input's click during a real touch event so WebView grants activation.
      target = visible(input) ? input : textarea;
      if (target && target !== input) {
        if (window.__arxivUploadTouch) document.removeEventListener('touchend', window.__arxivUploadTouch, true);
        const trigger = event => {
          document.removeEventListener('touchend', trigger, true);
          window.__arxivUploadTouch = null;
          event.preventDefault(); event.stopImmediatePropagation(); input.click();
        };
        window.__arxivUploadTouch = trigger;
        document.addEventListener('touchend', trigger, {capture: true, passive: false, once: true});
      }
    }
    if (!target) target = buttons.find(b => /attach|upload|paperclip/i.test(b.querySelector('svg')?.outerHTML || ''));
  }
  if (!target || !enabled(target)) return JSON.stringify({found:false});
  target.scrollIntoView({block:'nearest',inline:'nearest'});
  const r = target.getBoundingClientRect();
  return JSON.stringify({found:true,x:r.left+r.width/2-(window.visualViewport?.offsetLeft || 0),y:r.top+r.height/2-(window.visualViewport?.offsetTop || 0),width:window.visualViewport?.width || window.innerWidth});
})()
