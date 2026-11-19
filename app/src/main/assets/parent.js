(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var tokenKey = 'guoran-parent-session';
  var token = '';
  try { token = sessionStorage.getItem(tokenKey) || ''; } catch (error) { /* 禁用存储时，配对仅保留在当前页面内存。 */ }
  var state = null;
  var resourceOwner = 'family';
  var resourceFrom = 'family';
  var resourceBinding = null;
  var pickerPath = '', pickerStart = 0, pickerSequence = 0;
  var baiduPollTimer = null;

  var activeTab = 'watch';
  var watchDirty = false;
  var boxDirty = false;
  var shelfDirty = false;
  var resourceEditing = false;
  var resourceDirty = false;
  var resourceId = '';
  var resourceCover = 'course';
  var resourceCoverImage = '';
  var coverReading = false;
  var coverSequence = 0;
  var coverImages = Object.create(null);
  var mutating = false;
  var refreshing = false;
  var requestNumber = 0;
  var uploadRequest = null;
  var lastSynced = '';
  var kindNames = { video: '内置视频', baidu: '旧版网盘链接', baiduDrive: '自家网盘', link: '链接入口' };
  var coverNames = {
    frozen: ['❄', 'FROZEN'], sofia: ['♕', 'PRINCESS'], bluey: ['◒', 'CARTOON'],
    blocks: ['◇', 'BUILD'], dragon: ['✦', 'ADVENTURE'], space: ['☾', 'SPACE'],
    code: ['{ }', 'CODE'], course: ['Aa', 'LEARN']
  };
  var uploadNames = { uploaded: '已上传 · 待安装', awaiting_confirmation: '等待电视确认', installed: '已检测到安装', failed: '安装未完成' };

  function node(tag, className, text) {
    var item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = text;
    return item;
  }

  function playlist(id) {
    return state.playlists.find(function (item) { return item.id === id; });
  }

  function resourceShelf() {
    var selected = resourceOwner === 'family' ? null : playlist(resourceOwner);
    return { id: resourceOwner, name: selected ? selected.name : '首页精选',
      space: selected ? selected.name : '首页精选（不显示标题）',
      resources: selected ? selected.resources : state.familyResources };
  }

  function renderPlaylistOptions() {
    if (resourceOwner !== 'family' && !playlist(resourceOwner)) resourceOwner = 'family';
    ['resource-scope', 'resource-audience'].forEach(function (id) {
      var selected = $(id).value || 'family';
      var options = [node('option', '', '首页精选（不显示标题）')]; options[0].value = 'family';
      state.playlists.forEach(function (shelf) { var option = node('option', '', shelf.name); option.value = shelf.id; options.push(option); });
      replaceChildren($(id), options); $(id).value = id === 'resource-scope' ? resourceOwner : selected;
    });
  }

  function resourceBody(resource, owner) {
    var body = { child: owner, fromChild: owner, id: resource.id, title: resource.title, kind: resource.kind,
      url: resource.url, cover: resource.cover, pinned: resource.pinned };
    if (resource.kind === 'baiduDrive') body.baidu = resource.baidu;
    return body;
  }

  function notice(message, isError) {
    $('notice').textContent = message;
    $('notice').classList.toggle('error', !!isError);
    $('notice').hidden = !message;
  }

  function connection(online, message) {
    $('connection-dot').className = 'connection-dot ' + (online ? 'online' : 'offline');
    $('connection-status').textContent = online ? '盒子已连接 · ' + lastSynced + ' 同步' : '暂时无法连接盒子';
    $('connection-error').hidden = online;
    $('connection-error').textContent = online ? '' : (message || '连接中断。') + ' 已保留当前内容和未保存输入，稍后会自动重试。';
  }

  function saveToken(value) {
    token = value;
    try {
      if (value) sessionStorage.setItem(tokenKey, value);
      else sessionStorage.removeItem(tokenKey);
    } catch (error) { /* 页面内存仍可完成本次管理。 */ }
  }

  function showPair(message) {
    stopBaiduPolling(); pickerSequence++;
    saveToken('');
    if (uploadRequest) uploadRequest.abort();
    $('pair-view').hidden = false;
    $('control-view').hidden = true;
    $('disconnect').hidden = true;
    $('pair-error').textContent = message || '';
    $('pair-code').value = '';
  }

  // 所有普通接口统一处理返回契约；令牌只放请求头，绝不拼进地址。
  async function api(path, method, body, pairing) {
    var requestToken = token;
    var headers = { Accept: 'application/json' };
    if (!pairing) headers.Authorization = 'Bearer ' + token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    var response;
    try {
      response = await fetch(path, { method: method || 'GET', headers: headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store', credentials: 'omit' });
    } catch (error) {
      throw new Error('无法连接盒子，请检查手机与盒子是否连接同一局域网。');
    }
    if (response.status === 401 && !pairing) {
      if (requestToken === token) showPair('配对已失效，请查看电视「家长管理」中的新配对码。');
      throw new Error('配对已失效，请重新配对。');
    }
    var result;
    try { result = await response.json(); }
    catch (error) { throw new Error('盒子返回了无法识别的内容，请稍后重试。'); }
    if (!response.ok || result.code !== 0) throw new Error(result.message || '操作未完成，请重试。');
    return result.data;
  }

  async function refreshState(force) {
    if (!token || (refreshing && !force)) return false;
    var thisRequest = ++requestNumber;
    var requestToken = token;
    refreshing = true;
    $('refresh').disabled = true;
    try {
      var data = await api('/api/state');
      if (requestToken !== token || thisRequest !== requestNumber) return false;
      // 按请求先后排除迟到响应，revision 可随盒子进程重启而重新计数。
      state = data;
      lastSynced = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      connection(true);
      $('pair-view').hidden = true;
      $('control-view').hidden = false;
      $('disconnect').hidden = false;
      render();
      return true;
    } catch (error) {
      if (requestToken === token && token) connection(false, error.message);
      return false;
    } finally {
      if (thisRequest === requestNumber) {
        refreshing = false;
        $('refresh').disabled = false;
      }
    }
  }

  function updateDisabled() {
    document.querySelectorAll('[data-mutation]').forEach(function (item) { item.disabled = mutating || !state; });
    ['daily-limit', 'session-limit', 'break-minutes', 'box-name', 'new-playlist-name', 'start-time', 'end-time', 'shelf-name', 'resource-title', 'resource-kind', 'resource-cover', 'resource-url', 'resource-pinned', 'cover-file', 'resource-audience', 'resource-scope'].forEach(function (id) { $(id).disabled = mutating || !state; });
    $('resource-save').disabled = mutating || !state || coverReading;
    if (!state) return;
    $('extra-time').disabled = mutating || watchDirty;
    $('new-playlist-name').disabled = mutating || state.playlists.length >= 20;
    $('add-resource').disabled = mutating || resourceShelf().resources.length >= 60;
    $('upload-submit').disabled = mutating || !$('apk-file').files.length;
    $('apk-file').disabled = mutating;
    document.querySelectorAll('[data-reorder-edge]').forEach(function (item) { item.disabled = true; });
    document.querySelectorAll('[data-installed]').forEach(function (item) { item.disabled = true; });
    $('resource-url').disabled = mutating || $('resource-kind').value === 'baiduDrive';
    renderBaidu();
  }

  async function mutate(action, message, onSaved, errorId) {
    if (mutating || !token) return;
    mutating = true;
    notice('');
    if (errorId) $(errorId).textContent = '';
    updateDisabled();
    try {
      var saved = await action();
      if (!token) return;
      if (onSaved) onSaved(saved);
      var synced = await refreshState(true);
      if (token) notice(message + (synced ? '' : ' 已保存到盒子，状态暂未刷新；请稍后刷新查看。'));
    } catch (error) {
      if (token) {
        if (errorId) $(errorId).textContent = error.message;
        else notice(error.message, true);
      }
    } finally {
      mutating = false;
      if (token && state) render();
      updateDisabled();
    }
  }

  function replaceChildren(target, children) {
    while (target.firstChild) target.removeChild(target.firstChild);
    children.forEach(function (item) { target.appendChild(item); });
  }

  function emptyState(title, detail) {
    var box = node('div', 'empty-state');
    box.appendChild(node('span', 'empty-symbol', '◇'));
    box.appendChild(node('strong', '', title));
    box.appendChild(node('p', '', detail));
    return box;
  }

  function renderBox() {
    $('brand-name').textContent = state.displayName;
    document.title = state.displayName + ' · 家长管理';
    if (!boxDirty) $('box-name').value = state.displayName;
    $('box-draft').textContent = boxDirty ? '有未保存的名称' : '已与盒子同步';
    $('server-address').textContent = state.serverUrl;
    renderPlaylistOptions();
  }

  function renderWatch() {
    var p = state.watch;
    var used = Math.max(0, p.usedSeconds);
    var budget = p.dailyLimit * 60 + p.extraSeconds;
    var remaining = Math.max(0, budget - used);
    $('used-minutes').textContent = used > 0 && used < 60 ? '不到 1' : String(Math.floor(used / 60));
    $('used-minutes').appendChild(node('small', '', '分钟'));
    $('remaining-minutes').textContent = p.dailyLimit === 0 ? '不限每日总量' : Math.ceil(remaining / 60) + ' 分钟';
    var percent = p.dailyLimit === 0 ? 0 : Math.min(100, Math.round(used / budget * 100));
    $('usage-fill').style.width = percent + '%';
    $('usage-meter').setAttribute('aria-valuenow', String(percent));
    $('usage-meter').setAttribute('aria-valuetext', '全盒子已用 ' + Math.floor(used / 60) + ' 分钟');
    $('usage-caption').textContent = '连续使用 ' + p.sessionLimit + ' 分钟 / 休息 ' + p.breakMinutes + ' 分钟 · ' + (p.startTime === p.endTime ? '全天可用' : p.startTime + '–' + p.endTime);
    $('watch-state').textContent = p.blockedReason ? (p.restRemaining > 0 ? '休息中 ' + Math.ceil(p.restRemaining / 60) + ' 分钟' : '已暂停使用') : '按安排使用';
    $('watch-state').classList.toggle('paused', !!p.blockedReason);
    $('pause-watch').textContent = p.paused ? '恢复使用' : '暂停整个盒子';
    $('watch-child-caption').textContent = '整个盒子共用';
    $('watch-capability').textContent = state.capabilities.externalAppLimits ? '跨应用休息页已开启。' : '请在电视家长工具中开启「鼠标返回与首页」无障碍服务，才能对外部应用显示休息页。';
    if (!watchDirty) {
      $('daily-limit').value = p.dailyLimit;
      $('start-time').value = p.startTime;
      $('end-time').value = p.endTime;
      $('session-limit').value = p.sessionLimit;
      $('break-minutes').value = p.breakMinutes;
    }
    $('watch-draft').textContent = watchDirty ? '有未保存调整' : '已与盒子同步';
  }

  function actionButton(label, className, handler, ariaLabel) {
    var item = node('button', className, label);
    item.type = 'button';
    item.dataset.mutation = '';
    if (ariaLabel) item.setAttribute('aria-label', ariaLabel);
    item.addEventListener('click', handler);
    return item;
  }

  function renderResources() {
    var p = resourceShelf();
    $('resource-scope').value = resourceOwner;
    $('shelf-form').hidden = resourceOwner === 'family';
    $('resources-title').textContent = p.space;
    if (!shelfDirty) $('shelf-name').value = p.space;
    $('shelf-draft').textContent = shelfDirty ? '有未保存的栏目名称' : '已与盒子同步';
    $('resource-count').textContent = p.resources.length + ' 份资源 · ' + p.resources.filter(function (item) { return item.pinned; }).length + ' 份在首页';
    var cards = p.resources.map(function (resource, index) {
      var card = node('article', 'resource-card');
      var cover = node('span', 'resource-cover cover-' + resource.cover);
      cover.setAttribute('aria-hidden', 'true');
      renderCover(cover, resource.cover);
      card.appendChild(cover);
      var copy = node('div', 'resource-copy');
      copy.appendChild(node('h4', '', resource.title));
      copy.appendChild(node('p', 'resource-meta', kindNames[resource.kind] + ' · ' + (resource.kind === 'baiduDrive' ? (resource.baidu.isDir ? '已绑定文件夹' : '已绑定视频') : resource.url ? '已添加地址' : '待添加地址')));
      copy.appendChild(node('p', 'resource-url', resource.kind === 'baiduDrive' ? resource.baidu.path : resource.url ? '地址已保存 · 点击编辑可查看或修改' : '添加真实资源地址后即可打开'));
      var actions = node('div', 'resource-actions');
      var pinnedLabel = node('label', 'pin-label');
      var pinned = node('input');
      pinned.type = 'checkbox';
      pinned.checked = resource.pinned;
      pinned.dataset.mutation = '';
      pinned.setAttribute('aria-label', resource.title + '在' + p.name + '的首页显示');
      pinned.addEventListener('change', function () {
        var value = pinned.checked;
        var body = resourceBody(resource, p.id); body.pinned = value;
        mutate(function () { return api('/api/resource', 'POST', body); }, '已更新' + p.name + '的首页显示。', function () { resource.pinned = value; });
      });
      pinnedLabel.appendChild(pinned);
      pinnedLabel.appendChild(node('span', '', '首页显示'));
      actions.appendChild(pinnedLabel);
      var up = actionButton('↑', 'icon-button', function () { reorder(p, index, -1); }, '将' + resource.title + '上移');
      var down = actionButton('↓', 'icon-button', function () { reorder(p, index, 1); }, '将' + resource.title + '下移');
      if (index === 0) up.dataset.reorderEdge = '';
      if (index === p.resources.length - 1) down.dataset.reorderEdge = '';
      actions.appendChild(up);
      actions.appendChild(down);
      actions.appendChild(actionButton('编辑', 'icon-button edit', function () { openResource(resource); }, '编辑' + resource.title));
      actions.appendChild(actionButton('删除', 'icon-button delete', function () {
        if (!window.confirm('从' + p.name + '的内容列表中删除「' + resource.title + '」？')) return;
        mutate(function () { return api('/api/resource', 'DELETE', { child: p.id, id: resource.id }); }, '已删除这份资源。', function () {
          if (p.id === 'family') state.familyResources = state.familyResources.filter(function (item) { return item.id !== resource.id; });
          else playlist(p.id).resources = playlist(p.id).resources.filter(function (item) { return item.id !== resource.id; });
          if (resourceEditing && resourceId === resource.id) closeResource();
        });
      }, '删除' + resource.title));
      copy.appendChild(actions);
      card.appendChild(copy);
      return card;
    });
    if (!cards.length) cards.push(emptyState('书架还空着，先放一份喜欢的内容', '点击「添加」，填写视频直链、网盘或其他课程入口。示例名称不会自动带来视频内容。'));
    replaceChildren($('resource-list'), cards);
  }

  function reorder(p, index, direction) {
    var next = index + direction;
    if (next < 0 || next >= p.resources.length) return;
    var resources = p.resources.slice();
    var moving = resources[index];
    resources[index] = resources[next];
    resources[next] = moving;
    mutate(function () { return api('/api/resource/order', 'POST', { child: p.id, ids: resources.map(function (item) { return item.id; }) }); }, '已调整' + p.name + '的内容顺序。', function () { p.resources = resources; });
  }

  function resourceHelp() {
    var kind = $('resource-kind').value;
    $('resource-baidu').hidden = kind !== 'baiduDrive';
    $('resource-url-field').hidden = kind === 'baiduDrive';
    $('resource-url').disabled = kind === 'baiduDrive' || mutating;
    $('resource-url-help').hidden = kind === 'baiduDrive';
    $('resource-url-help').textContent = kind === 'video'
      ? '填写盒子可访问的视频直链；留空可先保存封面。'
      : kind === 'baidu' ? '这是旧版入口，TV 版不能从分享链接直达课程。请改选「自家百度网盘」并重新绑定自己的文件夹。'
        : '填写完整 http 或 https 链接。';
    $('resource-url').placeholder = kind === 'video' ? 'https://…/video.mp4' : 'https://…';
    $('resource-baidu-selection').textContent = resourceBinding ? (resourceBinding.isDir ? '文件夹：' : '视频：') + resourceBinding.path : '还没有选择课程目录或视频';
  }

  function renderBaidu() {
    var status = state.baidu;
    $('baidu-status').textContent = status.connected ? '已连接' : status.configured ? '待账号授权' : '待首次配置';
    $('baidu-description').textContent = status.configured ? '可访问目录：我的应用数据／' + status.appName + '。选择课程后在果然 TV 内浏览、播放。' : '首次需要配置自己的百度应用，再到官方页面授权账号。';
    $('baidu-authorize').disabled = mutating || !status.configured;
    $('baidu-authorize').textContent = status.connected ? '重新授权账号' : '连接百度账号';
    $('baidu-disconnect').hidden = !status.connected;
    $('choose-baidu').disabled = mutating || !status.connected;
    if (status.connected && !status.authorizing) $('baidu-authorization').hidden = true;
  }

  function stopBaiduPolling() { if (baiduPollTimer) clearTimeout(baiduPollTimer); baiduPollTimer = null; }

  function pollBaidu(interval, deadline) {
    stopBaiduPolling();
    baiduPollTimer = setTimeout(async function () {
      if (!token) return;
      if (Date.now() >= deadline) { $('baidu-error').textContent = '授权已过期，请重新点击「连接百度账号」。'; return; }
      try {
        var status = await api('/api/baidu/poll', 'POST', {});
        if (status.connected && !status.authorizing) { state.baidu = status; renderBaidu(); notice('百度网盘已连接，可以添加课程目录了。'); await refreshState(true); return; }
        if (!status.authorizing) { $('baidu-error').textContent = '授权未完成，请重新连接。'; return; }
        pollBaidu(interval, deadline);
      } catch (error) { $('baidu-error').textContent = error.message; pollBaidu(interval, deadline); }
    }, interval * 1000);
  }

  async function browseBaidu(path, start) {
    var sequence = ++pickerSequence;
    $('baidu-picker').hidden = false;
    $('baidu-picker-status').textContent = '正在读取目录…';
    replaceChildren($('baidu-picker-list'), []);
    $('baidu-picker-prev').hidden = true; $('baidu-picker-next').hidden = true;
    try {
      var result = await api('/api/baidu/list', 'POST', { path: path, start: start });
      if (sequence !== pickerSequence || !resourceEditing) return;
      pickerPath = result.path; pickerStart = start;
      $('baidu-picker-path').textContent = '我的应用数据／' + result.path.slice('/apps/'.length);
      $('baidu-picker-back').disabled = pickerPath === result.root;
      $('baidu-picker-status').textContent = result.entries.length ? '点文件夹进入；也可直接选择文件夹或视频。' : '这里还没有文件，请先在百度网盘中整理课程。';
      var items = result.entries.map(function (entry) {
        var row = node('div', 'picker-row');
        var label = actionButton((entry.isDir ? '▤  ' : entry.category === 1 ? '▶  ' : '·  ') + entry.name, 'picker-name', function () {
          if (entry.isDir) browseBaidu(entry.path, 0);
          else if (entry.category === 1) chooseBaidu(entry);
        });
        label.disabled = !entry.isDir && entry.category !== 1;
        row.appendChild(label);
        if (entry.isDir || entry.category === 1) row.appendChild(actionButton('选择', 'button secondary small', function () { chooseBaidu(entry); }));
        return row;
      });
      replaceChildren($('baidu-picker-list'), items);
      $('baidu-picker-prev').hidden = start === 0;
      $('baidu-picker-next').hidden = !result.hasMore;
    } catch (error) { if (sequence === pickerSequence) $('baidu-picker-status').textContent = error.message; }
  }

  function chooseBaidu(entry) {
    resourceBinding = { fsId: entry.fsId, path: entry.path, isDir: entry.isDir, category: entry.category };
    resourceDirty = true; pickerSequence++; $('baidu-picker').hidden = true;
    if (!$('resource-title').value.trim()) $('resource-title').value = entry.name;
    $('resource-error').textContent = ''; resourceHelp();
  }

  function renderCover(target, cover, image) {
    replaceChildren(target, []);
    if (image || /^custom-[a-f0-9]{64}$/.test(cover)) {
      var picture = node('img', 'custom-cover');
      picture.alt = '';
      target.appendChild(picture);
      if (image) picture.src = image;
      else {
        // 图片预览复用原有配对接口，缓存仅保留在当前家长页面内存中。
        if (!coverImages[cover]) coverImages[cover] = api('/api/cover', 'POST', { cover: cover }).then(function (result) { return result.image; }).catch(function () { delete coverImages[cover]; return ''; });
        coverImages[cover].then(function (data) { if (data) picture.src = data; });
      }
    } else {
      var artwork = coverNames[cover];
      if (artwork) {
        target.appendChild(node('span', '', artwork[0]));
        target.appendChild(node('small', '', artwork[1]));
      }
    }
  }

  function canDiscardResource() {
    return !resourceDirty || window.confirm('还有未保存的内容编辑，确认放弃这些输入？');
  }

  function openResource(resource) {
    if (!canDiscardResource()) return;
    resourceEditing = true;
    resourceDirty = false;
    resourceId = resource ? resource.id : '';
    var p = resourceShelf();
    resourceFrom = p.id;
    resourceBinding = resource && resource.kind === 'baiduDrive' ? resource.baidu : null;
    $('resource-audience').value = p.id;
    $('baidu-picker').hidden = true; pickerSequence++;
    $('resource-form').hidden = false;
    $('resource-form-title').textContent = resource ? '编辑内容' : '添加内容';
    $('resource-form-child').textContent = '选择所属片单；所有片单共用盒子的观看安排。';
    $('resource-title').value = resource ? resource.title : '';
    $('resource-title').placeholder = '填写你自己的内容名称';
    $('resource-kind').value = resource ? resource.kind : 'baiduDrive';
    $('resource-url').value = resource ? resource.url : '';
    resourceCover = resource ? resource.cover : 'course';
    resourceCoverImage = '';
    coverReading = false;
    coverSequence++;
    $('cover-file').value = '';
    $('resource-cover').value = resourceCover.startsWith('custom-') ? 'custom' : resourceCover;
    $('cover-status').textContent = '从手机相册选择图片，自动居中裁成 16:9 横版，并随内容一起保存到盒子。';
    renderCover($('cover-preview'), resourceCover);
    $('resource-pinned').checked = resource ? resource.pinned : true;
    $('resource-error').textContent = '';
    resourceHelp();
    $('resource-form').scrollIntoView({ behavior: 'smooth', block: 'start' });
    $('resource-title').focus({ preventScroll: true });
  }

  function closeResource() {
    resourceEditing = false;
    resourceDirty = false;
    resourceId = '';
    resourceBinding = null; pickerSequence++; $('baidu-picker').hidden = true;
    resourceCoverImage = '';
    coverReading = false;
    coverSequence++;
    $('resource-form').hidden = true;
    $('resource-form').reset();
    $('resource-error').textContent = '';
  }

  function renderApps() {
    $('apps-title').textContent = '首页「我的应用」';
    var visible = state.apps.filter(function (app) { return app.packageName !== 'com.dangbeimarket'; });
    $('apps-count').textContent = visible.filter(function (app) { return state.allowedApps.indexOf(app.packageName) !== -1; }).length + ' 个已开放';
    var apps = visible.map(function (app) {
      var row = node('div', 'app-row');
      var icon = node('span', 'app-icon', app.label.slice(0, 1));
      icon.setAttribute('aria-hidden', 'true');
      row.appendChild(icon);
      var copy = node('div', 'app-copy');
      copy.appendChild(node('strong', '', app.label));
      copy.appendChild(node('small', '', app.packageName + (app.versionName ? ' · ' + app.versionName : '')));
      row.appendChild(copy);
      var label = node('label', 'switch');
      var input = node('input');
      input.type = 'checkbox';
      input.checked = state.allowedApps.indexOf(app.packageName) !== -1;
      input.dataset.mutation = '';
      input.setAttribute('aria-label', '在首页显示' + app.label);
      input.addEventListener('change', function () {
        var allowed = input.checked;
        mutate(function () { return api('/api/apps', 'POST', { packageName: app.packageName, allowed: allowed }); }, '已' + (allowed ? '显示' : '隐藏') + '首页的「' + app.label + '」。', function () {
          state.allowedApps = state.allowedApps.filter(function (item) { return item !== app.packageName; });
          if (allowed) state.allowedApps.push(app.packageName);
        });
      });
      label.appendChild(input);
      label.appendChild(node('span', 'switch-track'));
      row.appendChild(label);
      return row;
    });
    if (!apps.length) apps.push(emptyState('还没有可管理的应用', '安装完成并刷新后，可在这里加入首页的「我的应用」。'));
    replaceChildren($('apps-list'), apps);
  }

  function formatSize(size) {
    return size >= 1024 * 1024 ? (size / (1024 * 1024)).toFixed(1) + ' MB' : Math.max(1, Math.round(size / 1024)) + ' KB';
  }

  function renderUploads() {
    var cards = state.uploads.map(function (upload) {
      var card = node('article', 'upload-card');
      var top = node('div', 'upload-card-top');
      var copy = node('div', 'upload-card-copy');
      copy.appendChild(node('h4', '', upload.label));
      copy.appendChild(node('p', 'tiny', upload.packageName + (upload.versionName ? ' · ' + upload.versionName : '')));
      copy.appendChild(node('p', 'tiny', formatSize(upload.size)));
      top.appendChild(copy);
      top.appendChild(node('span', 'pill subtle', uploadNames[upload.status] || upload.status));
      card.appendChild(top);
      var install = actionButton(upload.status === 'installed' ? '已检测到安装' : upload.status === 'awaiting_confirmation' ? '重新打开电视安装界面' : '到电视上确认安装', 'button secondary small', function () {
        var result;
        mutate(async function () { result = await api('/api/apk/install', 'POST', { id: upload.id }); }, '安装请求已发送到盒子。未安装时请在电视系统界面确认，完成后这里会自动检测。', function () { upload.status = result.status; });
      }, '在电视上安装' + upload.label);
      if (upload.status === 'installed') install.dataset.installed = '';
      card.appendChild(install);
      var remove = actionButton('移除安装包', 'text-button upload-remove', function () {
        if (!window.confirm('移除盒子上的「' + upload.label + '」安装包？\n这只会删除上传文件，不会卸载已安装的应用。')) return;
        mutate(function () { return api('/api/apk', 'DELETE', { id: upload.id }); }, '已移除上传安装包，已安装的应用不受影响。', function () {
          state.uploads = state.uploads.filter(function (item) { return item.id !== upload.id; });
        });
      }, '移除' + upload.label + '的上传安装包');
      card.appendChild(remove);
      return card;
    });
    if (!cards.length) cards.push(emptyState('还没有上传安装包', '从上方选择手机里的本地 APK。安装包上传与实际安装分开进行。'));
    replaceChildren($('uploads-list'), cards);
  }

  function render() {
    if (!state) return;
    renderBox();
    renderWatch();
    renderResources();
    renderApps();
    renderUploads();
    renderBaidu();
    updateDisabled();
  }

  function setTab(name) {
    activeTab = name;
    ['watch', 'resources', 'apps'].forEach(function (tab) {
      $('panel-' + tab).hidden = tab !== name;
      $('tab-' + tab).setAttribute('aria-selected', String(tab === name));
      $('tab-' + tab).tabIndex = tab === name ? 0 : -1;
    });
  }

  $('pair-form').addEventListener('submit', async function (event) {
    event.preventDefault();
    if ($('pair-submit').disabled) return;
    var code = $('pair-code').value.trim();
    if (!/^[0-9]{6}$/.test(code)) { $('pair-error').textContent = '请输入电视上显示的 6 位数字配对码。'; return; }
    $('pair-submit').disabled = true;
    $('pair-submit').textContent = '正在连接…';
    $('pair-error').textContent = '';
    try {
      var result = await api('/api/pair', 'POST', { code: code }, true);
      if (typeof result.token !== 'string' || !result.token) throw new Error('盒子未返回配对令牌，请重试。');
      saveToken(result.token);
      $('pair-code').value = '';
      state = null;
      watchDirty = false;
      shelfDirty = false;
      closeResource();
      var connected = await refreshState(true);
      if (!connected && token) {
        $('pair-view').hidden = true;
        $('control-view').hidden = false;
        $('disconnect').hidden = false;
        updateDisabled();
      }
    } catch (error) { $('pair-error').textContent = error.message; }
    finally { $('pair-submit').disabled = false; $('pair-submit').textContent = '连接我的果然 TV →'; }
  });

  $('pair-code').addEventListener('input', function () { this.value = this.value.replace(/[^0-9]/g, '').slice(0, 6); $('pair-error').textContent = ''; });
  $('disconnect').addEventListener('click', function () {
    if ((watchDirty || boxDirty || resourceDirty || shelfDirty) && !window.confirm('有未保存的修改，确认解除本机配对并放弃输入？')) return;
    watchDirty = false;
    shelfDirty = false;
    coverImages = Object.create(null);
    closeResource();
    showPair('已解除这台手机的配对。');
  });
  $('refresh').addEventListener('click', function () { refreshState(true); });

  $('resource-scope').addEventListener('change', function () {
    if ((resourceDirty || shelfDirty) && !window.confirm('有未保存的内容，确认放弃后切换栏目？')) { this.value = resourceOwner; return; }
    resourceOwner = this.value; shelfDirty = false; closeResource(); renderResources(); updateDisabled();
  });
  $('baidu-config-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var body = { appName: $('baidu-app-name').value.trim(), appKey: $('baidu-app-key').value.trim(), secretKey: $('baidu-secret-key').value.trim() };
    mutate(function () { return api('/api/baidu/config', 'POST', body); }, '应用配置已保存，请点击「连接百度账号」完成官方授权。', function () {
      stopBaiduPolling(); $('baidu-app-key').value = ''; $('baidu-secret-key').value = ''; $('baidu-setup').open = false; $('baidu-authorization').hidden = true;
    }, 'baidu-error');
  });
  $('baidu-authorize').addEventListener('click', function () {
    mutate(function () { return api('/api/baidu/authorize', 'POST', {}); }, '请打开百度官方授权页，确认后回到这里。', function (authorization) {
      $('baidu-auth-link').href = authorization.verificationUrl;
      $('baidu-user-code').textContent = authorization.userCode;
      $('baidu-authorization').hidden = false;
      pollBaidu(Math.max(6, authorization.interval), Date.now() + authorization.expiresIn * 1000);
    }, 'baidu-error');
  });
  $('baidu-disconnect').addEventListener('click', function () {
    if (!window.confirm('断开果然 TV 的网盘授权？已配置的课程和封面会保留，重新连接后可继续使用。')) return;
    mutate(function () { return api('/api/baidu/disconnect', 'POST', {}); }, '已断开网盘授权，课程配置已保留。', function () { stopBaiduPolling(); }, 'baidu-error');
  });
  $('choose-baidu').addEventListener('click', function () { browseBaidu(state.baidu.root, 0); });
  $('baidu-picker-back').addEventListener('click', function () {
    if (pickerPath !== state.baidu.root) browseBaidu(pickerPath.slice(0, pickerPath.lastIndexOf('/')), 0);
  });
  $('baidu-picker-prev').addEventListener('click', function () { browseBaidu(pickerPath, Math.max(0, pickerStart - 100)); });
  $('baidu-picker-next').addEventListener('click', function () { browseBaidu(pickerPath, pickerStart + 100); });

  document.querySelectorAll('[data-tab]').forEach(function (button) {
    button.addEventListener('click', function () { setTab(button.dataset.tab); });
    button.addEventListener('keydown', function (event) {
      var names = ['watch', 'resources', 'apps'];
      var index = names.indexOf(activeTab);
      if (event.key === 'ArrowRight') index = (index + 1) % names.length;
      else if (event.key === 'ArrowLeft') index = (index + names.length - 1) % names.length;
      else if (event.key === 'Home') index = 0;
      else if (event.key === 'End') index = names.length - 1;
      else return;
      event.preventDefault();
      setTab(names[index]);
      $('tab-' + names[index]).focus();
    });
  });

  $('box-form').addEventListener('input', function () { boxDirty = true; $('box-draft').textContent = '有未保存的名称'; });
  $('box-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var name = $('box-name').value.trim();
    if (!name || name.length > 24) { $('box-error').textContent = '请输入 1–24 个字的名称。'; return; }
    mutate(function () { return api('/api/box', 'POST', { displayName: name }); }, '已更新电视桌面名称。', function () { boxDirty = false; }, 'box-error');
  });
  $('pause-watch').addEventListener('click', function () {
    mutate(function () { return api('/api/box', 'POST', { watch: { paused: !state.watch.paused } }); }, '已更新整个盒子的暂停状态。');
  });
  $('extra-time').addEventListener('click', function () {
    mutate(function () { return api('/api/extra-time', 'POST', { minutes: 5 }); }, '已增加今天的 5 分钟额度，明天不会沿用。');
  });
  $('watch-form').addEventListener('input', function () { watchDirty = true; $('watch-error').textContent = ''; renderWatch(); updateDisabled(); });
  $('watch-form').addEventListener('change', function () { watchDirty = true; renderWatch(); updateDisabled(); });
  $('watch-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var watch = { dailyLimit: Number($('daily-limit').value), sessionLimit: Number($('session-limit').value), breakMinutes: Number($('break-minutes').value),
      startTime: $('start-time').value, endTime: $('end-time').value };
    if (!Number.isInteger(watch.dailyLimit) || watch.dailyLimit < 0 || watch.dailyLimit > 1440 || !Number.isInteger(watch.sessionLimit) || watch.sessionLimit < 1 || watch.sessionLimit > 180 || !Number.isInteger(watch.breakMinutes) || watch.breakMinutes < 1 || watch.breakMinutes > 180) {
      $('watch-error').textContent = '每日总量请填 0–1440 分钟，连续使用和休息请填 1–180 分钟。'; return;
    }
    mutate(function () { return api('/api/box', 'POST', { watch: watch }); }, '已保存整个盒子的观看安排。', function () { watchDirty = false; }, 'watch-error');
  });
  $('new-playlist-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var name = $('new-playlist-name').value.trim();
    if (!name || name.length > 24) { $('new-playlist-error').textContent = '请输入 1–24 个字的片单名称。'; return; }
    mutate(function () { return api('/api/playlist', 'POST', { name: name }); }, '片单已创建，添加课程后会显示在电视上。', function (saved) {
      state = saved; resourceOwner = saved.playlists[saved.playlists.length - 1].id; $('new-playlist-name').value = ''; shelfDirty = false;
    }, 'new-playlist-error');
  });
  $('delete-playlist').addEventListener('click', function () {
    if (!window.confirm('删除这个片单？课程和封面会移回首页精选。')) return;
    mutate(function () { return api('/api/playlist', 'DELETE', { id: resourceOwner }); }, '已删除片单，里面的课程移回首页精选。', function () { resourceOwner = 'family'; shelfDirty = false; closeResource(); });
  });

  $('add-resource').addEventListener('click', function () { openResource(null); });
  $('shelf-form').addEventListener('input', function () { shelfDirty = true; $('shelf-error').textContent = ''; $('shelf-draft').textContent = '有未保存的栏目名称'; });
  $('shelf-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var p = resourceShelf();
    var space = $('shelf-name').value.trim();
    if (!space || space.length > 24) { $('shelf-error').textContent = '栏目名称请填写 1–24 个字。'; return; }
    mutate(function () { return api('/api/playlist', 'POST', { id: p.id, name: space }); }, '已保存电视栏目名称。', function () { playlist(p.id).name = space; shelfDirty = false; }, 'shelf-error');
  });
  $('resource-cover').addEventListener('change', function () {
    if (this.value === 'custom') {
      if (!resourceCoverImage && !resourceCover.startsWith('custom-')) { $('cover-file').click(); return; }
    } else { resourceCover = this.value; resourceCoverImage = ''; coverSequence++; coverReading = false; }
    renderCover($('cover-preview'), resourceCover, resourceCoverImage);
    updateDisabled();
  });
  $('cover-file').addEventListener('change', async function () {
    var file = this.files[0];
    if (!file) return;
    var sequence = ++coverSequence;
    var objectUrl = '';
    coverReading = true;
    resourceDirty = true;
    $('resource-error').textContent = '';
    $('cover-status').textContent = '正在处理封面…';
    updateDisabled();
    try {
      if (!/^image\/(jpeg|png|webp)$/.test(file.type) || file.size > 10 * 1024 * 1024) throw new Error('请选择不超过 10MB 的 JPG、PNG 或 WebP 图片。');
      objectUrl = URL.createObjectURL(file);
      var image = new Image();
      await new Promise(function (resolve, reject) { image.onload = resolve; image.onerror = function () { reject(new Error('无法读取这张图片，请换一张 JPG 或 PNG。')); }; image.src = objectUrl; });
      if (sequence !== coverSequence) return;
      var canvas = document.createElement('canvas'); canvas.width = 960; canvas.height = 540;
      var cropWidth = Math.min(image.naturalWidth, image.naturalHeight * 16 / 9);
      var cropHeight = cropWidth * 9 / 16;
      canvas.getContext('2d').drawImage(image, (image.naturalWidth - cropWidth) / 2, (image.naturalHeight - cropHeight) / 2, cropWidth, cropHeight, 0, 0, 960, 540);
      var data;
      for (var quality of [0.85, 0.7, 0.5, 0.35]) {
        data = canvas.toDataURL('image/jpeg', quality);
        if (data.length <= 340000) break;
      }
      if (data.length > 340000) throw new Error('图片压缩后仍然过大，请选择一张更简单的图片。');
      resourceCover = 'custom'; resourceCoverImage = data;
      $('resource-cover').value = 'custom';
      renderCover($('cover-preview'), resourceCover, data);
      $('cover-status').textContent = '封面已准备好，点击「保存内容」后显示在电视上。';
    } catch (error) {
      if (sequence === coverSequence) { $('resource-error').textContent = error.message; $('cover-status').textContent = '已保留原封面，可重新选择图片。'; }
    } finally {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      if (sequence === coverSequence) { coverReading = false; updateDisabled(); }
    }
  });
  $('resource-cancel').addEventListener('click', function () { if (canDiscardResource()) closeResource(); });
  $('resource-form').addEventListener('input', function () { resourceDirty = true; $('resource-error').textContent = ''; });
  $('resource-form').addEventListener('change', function () { resourceDirty = true; resourceHelp(); });
  $('resource-form').addEventListener('submit', function (event) {
    event.preventDefault();
    if (coverReading) return;
    var p = resourceShelf();
    var title = $('resource-title').value.trim();
    var url = $('resource-kind').value === 'baiduDrive' ? '' : $('resource-url').value.trim();
    var kind = $('resource-kind').value;
    if (!title || title.length > 40) { $('resource-error').textContent = '请填写 1–40 个字的内容名称。'; return; }
    if (kind === 'baiduDrive' && !resourceBinding) { $('resource-error').textContent = '请先选择自家网盘中的课程文件夹或视频。'; return; }
    if (url) {
      try {
        var parsed = new URL(url);
        if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname || parsed.username || parsed.password) throw new Error();
        if (kind === 'baidu' && parsed.hostname !== 'pan.baidu.com') { $('resource-error').textContent = '百度网盘请填写 pan.baidu.com 的完整分享链接。'; return; }
      } catch (error) { $('resource-error').textContent = '请填写完整的 http 或 https 地址，也可以留空稍后添加。'; return; }
    }
    if ($('resource-cover').value === 'custom' && !resourceCoverImage && !resourceCover.startsWith('custom-')) { $('resource-error').textContent = '请先选择自己的封面图片，或使用内置封面。'; return; }
    var owner = $('resource-audience').value;
    var body = { child: owner, fromChild: resourceFrom, title: title, kind: kind, url: url, cover: resourceCover, pinned: $('resource-pinned').checked };
    if (kind === 'baiduDrive') body.baidu = resourceBinding;
    if (resourceCoverImage) body.coverImage = resourceCoverImage;
    if (resourceId) body.id = resourceId;
    mutate(function () { return api('/api/resource', 'POST', body); }, '内容已保存到「' + (owner === 'family' ? '首页精选' : playlist(owner).name) + '」。', function (saved) {
      state = saved; resourceOwner = owner; closeResource();
    }, 'resource-error');
  });

  function uploadFile(file) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      uploadRequest = xhr;
      xhr.open('POST', '/api/apk/upload');
      xhr.setRequestHeader('Authorization', 'Bearer ' + token);
      xhr.setRequestHeader('Content-Type', 'application/vnd.android.package-archive');
      xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
      // Content-Length 由浏览器按二进制文件大小设置，脚本不写受限请求头。
      xhr.timeout = 10 * 60 * 1000;
      xhr.upload.addEventListener('progress', function (event) {
        if (!event.lengthComputable) return;
        var value = Math.round(event.loaded / event.total * 100);
        $('upload-progress').value = value;
        $('upload-progress-label').textContent = value === 100 ? '传输完成，盒子正在检查安装包…' : '正在传到盒子 · ' + value + '%';
      });
      xhr.addEventListener('load', function () {
        uploadRequest = null;
        if (xhr.status === 401) { showPair('配对已失效，请重新配对后上传。'); reject(new Error('配对已失效。')); return; }
        var result;
        try { result = JSON.parse(xhr.responseText); }
        catch (error) { reject(new Error('盒子返回了无法识别的内容，请刷新后查看是否已上传。')); return; }
        if (xhr.status < 200 || xhr.status >= 300 || result.code !== 0) { reject(new Error(result.message || '上传未完成，请重试。')); return; }
        resolve(result.data);
      });
      xhr.addEventListener('error', function () { uploadRequest = null; reject(new Error('上传连接中断，文件仍保留在手机上。请检查局域网连接后重试。')); });
      xhr.addEventListener('timeout', function () { uploadRequest = null; reject(new Error('上传超时，请检查局域网连接后重试。')); });
      xhr.addEventListener('abort', function () { uploadRequest = null; reject(new Error('上传已取消。')); });
      xhr.send(file);
    });
  }

  $('apk-file').addEventListener('change', function () {
    var file = this.files[0];
    $('upload-error').textContent = '';
    $('upload-progress-wrap').hidden = true;
    $('file-label').textContent = file ? file.name : '选择手机里的 APK';
    $('file-detail').textContent = file ? formatSize(file.size) + ' · 仅上传到当前盒子' : '单个安装包不超过 100 MB';
    updateDisabled();
  });
  $('upload-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var file = $('apk-file').files[0];
    if (!file) { $('upload-error').textContent = '请先选择手机里的 APK 文件。'; return; }
    if (!/\.apk$/i.test(file.name)) { $('upload-error').textContent = '请选择以 .apk 结尾的 Android 安装包。'; return; }
    if (file.size <= 0 || file.size > 100 * 1024 * 1024) { $('upload-error').textContent = '安装包须大于 0，且不超过 100 MB。'; return; }
    $('upload-progress-wrap').hidden = false;
    $('upload-progress').value = 0;
    $('upload-progress-label').textContent = '正在准备上传…';
    var upload;
    mutate(async function () { upload = await uploadFile(file); }, 'APK 已传到盒子，安装状态以盒子检测为准。未安装时，请打开安装界面，再到电视上确认。', function () {
      // 上传记录来自接口；仅安装检测可将它标记为已安装。
      state.uploads = state.uploads.filter(function (item) { return item.id !== upload.id; });
      state.uploads.unshift(upload);
      $('upload-progress').value = 100;
      $('upload-progress-label').textContent = '安装包已到盒子 · 安装状态以盒子检测为准';
      $('apk-file').value = '';
      $('file-label').textContent = '选择手机里的 APK';
      $('file-detail').textContent = '单个安装包不超过 100 MB';
    }, 'upload-error');
  });

  window.addEventListener('beforeunload', function (event) {
    if (!watchDirty && !boxDirty && !resourceDirty && !shelfDirty && !uploadRequest) return;
    event.preventDefault();
    event.returnValue = '';
  });
  document.addEventListener('visibilitychange', function () { if (!document.hidden && token) refreshState(true); });
  window.addEventListener('online', function () { if (token) refreshState(true); });
  window.setInterval(function () { if (token && !document.hidden && !mutating) refreshState(false); }, 5000);

  updateDisabled();
  if (token) {
    $('pair-view').hidden = true;
    $('control-view').hidden = false;
    $('disconnect').hidden = false;
    refreshState(true);
  }
}());
