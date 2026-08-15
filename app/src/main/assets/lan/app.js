(function () {
  'use strict';

  var MAX_BYTES = 200 * 1024 * 1024;
  var ALLOWED = /\.(txt|epub)$/i;

  var drop = document.getElementById('drop');
  var picker = document.getElementById('picker');
  var queue = document.getElementById('queue');

  drop.addEventListener('click', function () { picker.click(); });
  drop.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      picker.click();
    }
  });
  picker.addEventListener('change', function () {
    enqueue(picker.files);
    picker.value = '';
  });

  ['dragenter', 'dragover'].forEach(function (name) {
    drop.addEventListener(name, function (event) {
      event.preventDefault();
      drop.classList.add('over');
    });
  });
  ['dragleave', 'drop'].forEach(function (name) {
    drop.addEventListener(name, function (event) {
      event.preventDefault();
      drop.classList.remove('over');
    });
  });
  drop.addEventListener('drop', function (event) {
    if (event.dataTransfer) enqueue(event.dataTransfer.files);
  });
  // 拖到页面空白处不要让浏览器直接打开文件
  window.addEventListener('dragover', function (e) { e.preventDefault(); });
  window.addEventListener('drop', function (e) { e.preventDefault(); });

  var pending = [];
  var busy = false;

  function enqueue(fileList) {
    Array.prototype.slice.call(fileList || []).forEach(function (file) {
      var row = addRow(file.name);
      if (!ALLOWED.test(file.name)) {
        finish(row, false, '只支持 txt / epub');
        return;
      }
      if (file.size > MAX_BYTES) {
        finish(row, false, '超过 200 MB');
        return;
      }
      pending.push({ file: file, row: row });
    });
    pump();
  }

  function pump() {
    if (busy) return;
    var next = pending.shift();
    if (!next) return;
    busy = true;
    upload(next.file, next.row, function () {
      busy = false;
      pump();
    });
  }

  function upload(file, row, done) {
    var request = new XMLHttpRequest();
    request.open('POST', '/upload', true);
    // 文件名走头部而非 multipart：服务端按 Content-Length 精确读取，最省事也最不易出错。
    request.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
    request.setRequestHeader('Content-Type', 'application/octet-stream');

    request.upload.onprogress = function (event) {
      if (event.lengthComputable) progress(row, event.loaded / event.total);
    };
    request.onload = function () {
      if (request.status >= 200 && request.status < 300) {
        finish(row, true, '已送达手机');
      } else {
        finish(row, false, describeError(request.responseText, request.status));
      }
      done();
    };
    request.onerror = function () {
      finish(row, false, '连接中断');
      done();
    };
    request.send(file);
  }

  function describeError(body, status) {
    try {
      var parsed = JSON.parse(body);
      if (parsed && parsed.error) return parsed.error;
    } catch (ignored) { /* 非 JSON 响应就用状态码兜底 */ }
    return '上传失败（' + status + '）';
  }

  function addRow(name) {
    queue.hidden = false;
    var item = document.createElement('div');
    item.className = 'item';
    item.innerHTML =
      '<div class="item-head">' +
      '<span class="item-name"></span>' +
      '<span class="item-state">等待中</span>' +
      '</div><div class="bar"><span></span></div>';
    item.querySelector('.item-name').textContent = name;
    queue.appendChild(item);
    return item;
  }

  function progress(row, fraction) {
    var percent = Math.max(0, Math.min(100, Math.round(fraction * 100)));
    row.querySelector('.bar span').style.width = percent + '%';
    row.querySelector('.item-state').textContent = percent + '%';
  }

  function finish(row, ok, message) {
    row.classList.add(ok ? 'done' : 'fail');
    row.querySelector('.item-state').textContent = message;
  }
})();
