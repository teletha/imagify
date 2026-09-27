/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

/**
 * The stylesheet and the script the comparison reports share.
 *
 * <p>Kept in one place so the format report and the resize report cannot drift apart. A reader who
 * has learned to drag a comparison slider in one of them should not have to learn it again in the
 * other, and a change to how a table sorts belongs in both.</p>
 */
final class ReportAssets {

    private ReportAssets() {}

    /**
     * The page shell, the typography, the tables, and the drag to wipe comparison.
     *
     * <p>Anything a single report needs on its own, such as a highlight for the winning row, goes
     * in that report's own stylesheet instead of here.</p>
     */
    static String css() {
        return ""
            + ":root{--zoom:2}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;padding:32px;font:14px/1.6 -apple-system,'Segoe UI','Noto Sans JP',sans-serif;"
            + "color:#1b1f23;background:#fff}"
            + "h1{font-size:24px;margin:0 0 8px}"
            + "h2{font-size:19px;margin:40px 0 4px;padding-bottom:6px;border-bottom:2px solid #1b1f23}"
            + "h3{font-size:15px;margin:28px 0 8px;font-weight:600}"
            + ".lead{margin:0 0 8px;color:#57606a;max-width:70ch}"
            + ".note{margin:0 0 12px;color:#57606a}"
            + "table{border-collapse:collapse;font-size:13px;margin-bottom:8px}"
            + "th,td{border:1px solid #d0d7de;padding:5px 9px;white-space:nowrap}"
            + "th{background:#f6f8fa;cursor:pointer;user-select:none}"
            + "th:hover{background:#eaeef2}"
            + "td.num{text-align:right;font-variant-numeric:tabular-nums}"
            + "td small{display:block;color:#6e7781;font-size:11px}"
            + "td.name{font-weight:600}"
            + "td.name a{color:#0969da;text-decoration:none}"
            + "td.name a:hover{text-decoration:underline}"
            + ".tag{font-size:11px;font-weight:400;color:#9a6700;background:#fff8c5;padding:0 4px;"
            + "border-radius:3px}"
            + ".zoom{margin:0 0 20px}"
            + ".zoom button{font:inherit;padding:4px 12px;border:1px solid #d0d7de;background:#f6f8fa;"
            + "cursor:pointer}"
            + ".zoom button:first-child{border-radius:6px 0 0 6px}"
            + ".zoom button:last-child{border-radius:0 6px 6px 0}"
            + ".zoom button+button{border-left:none}"
            + ".zoom button.on{background:#1b1f23;color:#fff;border-color:#1b1f23}"
            + ".sample{border-top:1px solid #d0d7de;padding-top:4px}"
            + ".grid{display:flex;flex-wrap:wrap;gap:16px}"
            + ".cmp{margin:0;width:calc(var(--w) * var(--zoom));flex:0 0 auto}"
            + ".stack{position:relative;width:100%;aspect-ratio:var(--ar);overflow:hidden;line-height:0;"
            + "border:1px solid #d0d7de;border-radius:6px;cursor:ew-resize;"
            + "background:repeating-conic-gradient(#e9edf1 0 25%,#fff 0 50%) 0 0/16px 16px;"
            + "user-select:none;-webkit-user-select:none}"
            + ".stack img{position:absolute;top:0;left:0;display:block;image-rendering:pixelated}"
            + ".stack .after{width:100%;height:100%}"
            + ".stack .clip{position:absolute;top:0;left:0;height:100%;width:50%;overflow:hidden}"
            + ".stack .before{height:100%;width:auto;max-width:none}"
            + ".stack .split{position:absolute;top:0;bottom:0;left:50%;width:1px;background:#fff;"
            + "box-shadow:0 0 0 1px rgba(0,0,0,.45);pointer-events:none}"
            + "figcaption{font-size:11px;color:#57606a;padding-top:4px;line-height:1.4;word-break:break-word}"
            + "@media(prefers-color-scheme:dark){"
            + "body{background:#0d1117;color:#e6edf3}"
            + "h2{border-color:#e6edf3}"
            + ".lead,.note,figcaption{color:#8b949e}"
            + "th{background:#161b22}th:hover{background:#21262d}"
            + "th,td{border-color:#30363d}.stack{border-color:#30363d}"
            + "td small{color:#8b949e}"
            + "td.name a{color:#4493f8}}";
    }

    /**
     * Zoom buttons, drag to wipe between two images, and click a heading to sort.
     *
     * <p>The wipe carries a one pixel divider that follows the pointer. A report emits it only if
     * it wants the line, and the script copes either way.</p>
     */
    static String js() {
        return ""
            + "document.querySelectorAll('.zoom button').forEach(function(b){"
            + "  b.addEventListener('click',function(){"
            + "    document.documentElement.style.setProperty('--zoom',b.dataset.zoom);"
            + "    document.querySelectorAll('.zoom button').forEach(function(o){"
            + "      o.classList.toggle('on',o===b);});});});"
            + "document.querySelectorAll('.stack .clip').forEach(function(clip){"
            + "  var stack=clip.parentNode;"
            + "  var split=stack.querySelector('.split');"
            + "  function move(e){"
            + "    var r=stack.getBoundingClientRect();"
            + "    var ratio=(e.clientX-r.left)/r.width;"
            + "    var p=Math.min(1,Math.max(0,ratio))*100;"
            + "    clip.style.width=p+'%';"
            + "    if(split)split.style.left=p+'%';}"
            + "  stack.addEventListener('pointerdown',function(e){"
            + "    stack.setPointerCapture(e.pointerId);move(e);});"
            + "  stack.addEventListener('pointermove',function(e){"
            + "    if(e.buttons)move(e);});"
            + "});"
            + "function sortBy(table,index,asc){"
            + "  var body=table.tBodies[0];"
            + "  var rows=Array.prototype.slice.call(body.rows);"
            + "  var key=function(row){"
            + "    var text=row.cells[index]?row.cells[index].textContent.trim():'';"
            + "    var num=parseFloat(text.replace('%',''));"
            + "    return isNaN(num)?text.toLowerCase():num;};"
            + "  rows.sort(function(a,b){var x=key(a),y=key(b);"
            + "    return x<y?(asc?-1:1):x>y?(asc?1:-1):0;});"
            + "  rows.forEach(function(r){body.appendChild(r);});}"
            + "document.querySelectorAll('table').forEach(function(table){"
            + "  table.querySelectorAll('th').forEach(function(th,index){"
            + "    var asc=true;"
            + "    th.addEventListener('click',function(){asc=!asc;sortBy(table,index,asc);});});});";
    }
}
