/* Dependency-free browser client (Leaflet is loaded only for map display). */
'use strict';
const $ = sel => document.querySelector(sel);
const $$ = sel => Array.from(document.querySelectorAll(sel));
const STAGES={DISCUSSION:'Обсуждение',PREPARATION:'Подготовка',PROCUREMENT:'Закупка',APPROVAL:'Утверждено',WORKS:'Работы',OTHER:'Другое'};
const SOURCE_NAMES={DEMO:'Демонстрация',DISPUTE:'dispute.kzn.ru',DISPUTE_EXAMPLE:'Пример · dispute.kzn.ru',PRAVO:'pravo.tatarstan.ru',GRAD:'kzn.ru',DVOR:'dvor.tatar',KZN_ACT:'Акты Казани',RT_MINTRANS:'Миндортранс РТ',EIS_ROADS:'ЕИС · HTML',EIS_SOAP:'ЕИС · SOAP',KZN_PDF_ANNEX:'PDF актов Казани',RT_PDF_ANNEX:'PDF Кабмина РТ',LOCAL_PDF_ANNEX:'Локальный PDF',EIS_INBOX:'ЕИС · архив',MANUAL:'Вручную'};
let areas=[],events=[],sources=[],map=null,areaLayer=null,markerLayer=null,drawingLayer=null,drawPoints=[],drawMode=false,placeEventId=null,chosen=null;
let sourceBusy=false,lastSourceCheck=0,noticeTimer,parcelAreaId=null;
function h(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function date(s){if(!s)return 'дата неизвестна';let v=new Date(s);return isNaN(v)?h(s):v.toLocaleDateString('ru-RU',{day:'2-digit',month:'short',year:'numeric'});}
async function api(path,method='GET',obj){let res=await fetch(path,{method,headers:obj?{'Content-Type':'application/json'}:{},body:obj?JSON.stringify(obj):undefined});let body=await res.json();if(!res.ok)throw new Error(body.error||('HTTP '+res.status));return body;}
function toast(message){let el=$('#toast');el.textContent=message;el.classList.remove('hidden');clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>el.classList.add('hidden'),4400);}
async function refresh(){try{[areas,events]=await Promise.all([api('/api/areas'),api('/api/events')]);renderAreas();renderEvents();renderMap();renderStats();}catch(e){toast('Ошибка загрузки: '+e.message);}}
function renderStats(){let active=events.filter(e=>!e.dismissed);let matched=active.filter(e=>e.relevance==='MATCH').length;let uncertain=active.filter(e=>e.relevance==='UNLOCATED'||e.relevance==='HINT').length;$('#metricAreas').textContent=areas.length;$('#metricMatch').textContent=matched;$('#metricReview').textContent=uncertain;}
function renderAreas(){let wrap=$('#areaList');if(!areas.length){wrap.innerHTML='<p class="empty">Пока нет территорий. Нарисуйте первый полигон.</p>';return;}wrap.innerHTML=areas.map((a,i)=>`<div class="area-card"><span class="area-chip">⬡</span><div class="area-body"><strong>${h(a.name)}</strong><p>${a.keywords?'Метки: '+h(a.keywords):'Без ручных меток'} · Улиц OSM: ${h(a.streetCount||0)} · Участков: ${h(a.parcelCount||0)}</p></div><button title="Загрузить названия улиц OSM (Overpass, вручную)" data-streets="${h(a.id)}">Улицы</button><button title="Импорт кадастровых номеров/GeoJSON" data-parcels="${h(a.id)}">Участки</button><button title="Показать на карте" data-focus="${h(a.id)}">⌖</button><button title="Удалить территорию" data-remove="${h(a.id)}">×</button></div>`).join('');}
function stageSort(e){let score=0;if(e.relevance==='MATCH')score+=80;if(e.relevance==='HINT')score+=50;if(e.relevance==='UNLOCATED')score+=30;if(e.stage==='PREPARATION'||e.stage==='DISCUSSION')score+=25;if(!e.seen)score+=10;return score;}
function filterEvents(){let f=$('#filter').value,q=$('#search').value.trim().toLowerCase();return events.filter(e=>{
 if(f==='dismissed'?!e.dismissed:e.dismissed)return false;
 if(f==='match'&&e.relevance!=='MATCH')return false;
 if(f==='uncertain'&&e.relevance!=='UNLOCATED'&&e.relevance!=='HINT')return false;
 if(f==='unread'&&e.seen)return false;
 return !q || [e.title,e.description,e.source,e.program||'',e.authority||'',...e.areas,...e.hints].join(' ').toLowerCase().includes(q);
}).sort((a,b)=>stageSort(b)-stageSort(a)||b.firstSeen.localeCompare(a.firstSeen));}
function statusText(e){if(e.relevance==='MATCH')return '● В вашей зоне';if(e.relevance==='HINT')return '◐ Есть текстовая подсказка';if(e.relevance==='OUTSIDE')return '○ Вне полигонов';return '◌ Требует геопривязки';}
function renderEvents(){let view=filterEvents();$('#feedCount').textContent=view.length+' / '+events.length;let wrap=$('#eventList');if(!view.length){wrap.innerHTML='<div class="empty">По этому фильтру событий нет. Попробуйте «Все события».</div>';return;}
wrap.innerHTML=view.map(e=>`<article class="event-card ${e.relevance.toLowerCase()} ${e.seen?'seen':''}" data-event="${h(e.id)}" tabindex="0" role="button" aria-label="Открыть событие"><div class="event-accent"></div><div class="event-main"><div class="event-topline"><span class="pill ${e.stage.toLowerCase()}">${h(STAGES[e.stage]||'Событие')}</span>${e.demo?'<span class="pill demo">Пример</span>':''}<span class="source-label">${h(SOURCE_NAMES[e.source]||e.source)} · ${date(e.publishedAt||e.firstSeen)}</span></div><h3>${h(e.title)}</h3><p>${h(statusText(e))} · ${h(e.description)}</p></div><div class="event-side"><span>${e.seen?'Просмотрено':'Новое'}</span><span class="arrow">›</span></div></article>`).join('');}
function initMap(){if(!window.L){$('#mapFallback').classList.remove('hidden');return;}
map=L.map('map',{zoomControl:true,doubleClickZoom:false}).setView([55.798,49.107],12);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(map);
areaLayer=L.featureGroup().addTo(map);markerLayer=L.featureGroup().addTo(map);drawingLayer=L.featureGroup().addTo(map);
map.on('click',event=>{
 if(placeEventId){const id=placeEventId;placeEventId=null;map.getContainer().style.cursor='';
    api('/api/events/'+encodeURIComponent(id),'PATCH',{position:[event.latlng.lng,event.latlng.lat]}).then(()=>refresh().then(()=>{selectEvent(id);toast('Точка сохранена. Совпадение с полигонами рассчитано.');})).catch(e=>toast(e.message));return;}
 if(drawMode){drawPoints.push([event.latlng.lng,event.latlng.lat]);drawPreview();}
});
}
function renderMap(){if(!map)return;areaLayer.clearLayers();markerLayer.clearLayers();for(const area of areas){try{let layer=L.geoJSON({type:'Feature',geometry:area.geometry,properties:{name:area.name}},{style:{color:'#008f92',weight:2,fillColor:'#5fbdb5',fillOpacity:0.14,dashArray:'7 4'}});layer.bindPopup('<b>'+h(area.name)+'</b>');areaLayer.addLayer(layer);}catch(e){console.warn(e);}}
for(const e of events){if(!e.position)continue;const hit=e.relevance==='MATCH';let marker=L.circleMarker([e.position[1],e.position[0]],{radius:hit?9:7,color:'#fff',weight:2,fillColor:hit?'#cb8b36':'#548aab',fillOpacity:.95});marker.bindTooltip(h(e.title),{direction:'top'});marker.on('click',()=>selectEvent(e.id));markerLayer.addLayer(marker);}
}
function drawPreview(){if(!map)return;drawingLayer.clearLayers();const latlngs=drawPoints.map(p=>[p[1],p[0]]);if(latlngs.length){L.polyline(latlngs,{color:'#aa6418',dashArray:'5 4',weight:3}).addTo(drawingLayer);for(let p of latlngs)L.circleMarker(p,{radius:4,color:'#fff',fillColor:'#b77422',fillOpacity:1,weight:1}).addTo(drawingLayer);}$('#drawHint').textContent=`Вершин: ${drawPoints.length}. Для полигона нужно минимум три.`;}
function stopDrawing(){drawMode=false;drawPoints=[];if(drawingLayer)drawingLayer.clearLayers();$('#drawTools').classList.add('hidden');$('#startDrawBtn').disabled=false;}
function startDrawing(){if(!map){toast('Карта пока не загружена — доступ к Leaflet/OSM необходим.');return;}placeEventId=null;drawMode=true;drawPoints=[];$('#drawTools').classList.remove('hidden');$('#startDrawBtn').disabled=true;$('#drawHint').textContent='Кликайте по карте, чтобы поставить вершины.';toast('Поставьте вершины по границе территории.');}
async function finishDrawing(){if(drawPoints.length<3){toast('Нужны хотя бы три вершины.');return;}
 let name=prompt('Название отслеживаемой территории','Новая территория');if(name===null)return;name=name.trim();if(!name){toast('Название обязательно.');return;}
 let keywords=prompt('Необязательные ключевые слова (улицы, кадастровые номера) через ;','')||'';
 let ring=[...drawPoints,drawPoints[0]],geometry={type:'Polygon',coordinates:[ring]};
 try{await api('/api/areas','POST',{name,keywords,geometry});stopDrawing();await refresh();toast('Полигон сохранён. Текстовые подсказки не заменяют геопривязку.');}catch(e){toast(e.message);}}
function closeDrawer(){chosen=null;$('#detailBackdrop').classList.add('hidden');$('#detailDrawer').classList.add('hidden');}
function selectEvent(id){const e=events.find(e=>e.id===id);if(!e)return;chosen=id;const areaInfo=e.relevance==='MATCH'?'Совпадает с: '+e.areas.join(', '):e.relevance==='HINT'?'Найдены географические/текстовые признаки (требуют проверки): '+e.hints.join(', '):e.relevance==='UNLOCATED'?'Не определена география: событие нельзя исключать из ваших территорий.':'Точка находится вне отмеченных полигонов.';
const evidenceHtml=(e.geoEvidence||[]).length?`<div class="drawer-section"><h3>Почему событие найдено</h3>${e.geoEvidence.slice(0,12).map(x=>`<p><strong>${h(x.area)}</strong> · ${h(x.kind)} · «${h(x.matchedText)}» (${h(x.score)})<br>${h(x.explanation)}</p>`).join('')}<p><small>Совпадение названия или кадастрового номера не доказывает геометрию самих работ.</small></p></div>`:'';
const safeUrl=/^https?:\/\//i.test(e.url||'')?e.url:null;
$('#detailDrawer').innerHTML=`<div class="drawer-top"><strong>КАРТОЧКА СОБЫТИЯ</strong><button class="close" id="closeDrawer" aria-label="Закрыть">×</button></div><div style="margin-top:24px"><span class="pill ${e.stage.toLowerCase()}">${h(STAGES[e.stage]||'Другое')}</span> ${e.demo?'<span class="pill demo">Иллюстрация/пример</span>':''}</div><h2>${h(e.title)}</h2><div class="meta"><span>Источник: ${h(SOURCE_NAMES[e.source]||e.source)}</span><span>Обнаружено: ${date(e.firstSeen)}</span><span>Опубликовано: ${date(e.publishedAt)}</span>${e.issuedAt?`<span>Дата акта: ${date(e.issuedAt)}</span>`:''}${e.program?`<span>Программа: ${h(e.program)}</span>`:''}${e.authority?`<span>Орган/заказчик: ${h(e.authority)}</span>`:''}${e.locationScope?`<span>Привязка источника: ${h(e.locationScope)}</span>`:''}${e.annexPage?`<span>Страница приложения: ${h(e.annexPage)}</span>`:''}</div><p>${h(e.description||'Описание отсутствует. Проверьте документ.')}</p>${e.requiresReview?'<p><strong>Требуется проверить географию и приложения к документу.</strong></p>':''}<div class="tag ${e.relevance==='UNLOCATED'?'warning':''}">${h(areaInfo)}</div>${evidenceHtml}<div class="drawer-buttons">${safeUrl?`<a class="button primary" target="_blank" rel="noopener noreferrer" href="${h(safeUrl)}">Открыть первоисточник ↗</a>`:''}<button class="button" id="geoEvent">⌖ Указать точку</button><button class="button" id="seenEvent">${e.seen?'↶ Не просмотрено':'✓ Просмотрено'}</button><button class="button" id="dismissEvent">${e.dismissed?'↶ Вернуть в ленту':'⊘ Скрыть'}</button></div><div class="drawer-section"><h3>Срок реакции</h3><p>Дата не извлекается автоматически из HTML: переносите её только из первоисточника. Наличие обсуждений не означает, что любой человек вправе формально участвовать.</p><input id="eventDeadline" type="date" value="${/^\d{4}-\d{2}-\d{2}$/.test(e.deadline||'')?h(e.deadline):''}"/><button class="button" id="deadlineSave">Сохранить</button></div><div class="drawer-section"><h3>Ваши заметки и действия</h3><textarea id="eventNote" maxlength="2500" placeholder="Что нужно изучить? Какие вопросы задать?">${h(e.note)}</textarea><div class="drawer-buttons"><button class="button primary" id="noteSave">Сохранить заметку</button></div></div><div class="drawer-section"><p>Статус проекта определяется по ключевым словам. «Подготовка», «Обсуждение» и «Утверждение» должны затем объединяться в историю одного проекта.</p></div>`;
$('#detailBackdrop').classList.remove('hidden');$('#detailDrawer').classList.remove('hidden');
$('#closeDrawer').onclick=closeDrawer;
$('#seenEvent').onclick=()=>updateEvent(e.id,{seen:!e.seen});
$('#dismissEvent').onclick=()=>updateEvent(e.id,{dismissed:!e.dismissed});
$('#noteSave').onclick=()=>updateEvent(e.id,{note:$('#eventNote').value});
$('#deadlineSave').onclick=()=>updateEvent(e.id,{deadline:$('#eventDeadline').value});
$('#geoEvent').onclick=()=>{if(!map){toast('Карта не загрузилась.');return;}placeEventId=e.id;closeDrawer();map.getContainer().style.cursor='crosshair';toast('Теперь кликните по реальному месту на карте. Это ручная привязка.');};
api('/api/related?eventId='+encodeURIComponent(e.id)).then(data=>{
  if(chosen!==e.id||!data.candidates.length)return;
  const section=document.createElement('div');section.className='drawer-section';
  section.innerHTML='<h3>Возможные связанные документы</h3>'+
    data.candidates.slice(0,8).map(x=>`<p><button class="button" data-related="${h(x.eventId)}">${h(x.title.slice(0,130))}</button> · ${h(x.relation)} (оценка ${h(x.score)})</p>`).join('')+
    '<small>Совпадение объекта не подтверждает, что это один проект.</small>';
  $('#detailDrawer').appendChild(section);
  section.addEventListener('click',click=>{const related=click.target.closest('[data-related]');if(related)selectEvent(related.dataset.related);});
}).catch(()=>{});
}
async function updateEvent(id,patch){try{await api('/api/events/'+encodeURIComponent(id),'PATCH',patch);await refresh();selectEvent(id);toast('Сохранено');}catch(e){toast(e.message);}}
async function sourceStatus(){try{const data=await api('/api/sources');sourceBusy=data.busy;sources=data.sources;$('#scanBtn').disabled=sourceBusy;$('#scanBtn').textContent=sourceBusy?'◷ Сканирование...':'↻ Проверить источники';$('#syncIndicator').innerHTML=sourceBusy?'<i style="background:#facb83"></i> Проверка сайтов':'<i></i> Локальный прототип';$('#sourcesList').innerHTML=sources.map(s=>`<div class="source-card"><header><a href="${h(s.url)}" target="_blank" rel="noopener">${h(s.title)} ↗</a></header><small class="source-status">${h(s.state)}</small><small>Последняя попытка: ${s.checkedAt?date(s.checkedAt):'не было'} · Новых: ${s.added}</small>${s.message?`<small>${h(s.message)}</small>`:''}</div>`).join('');}catch(e){console.warn('source status:',e);}}
async function scan(){try{let ans=await api('/api/scan','POST',{});toast(ans.queued?'Запущена проверка. Статусы видны на вкладке «Источники».':'Проверка уже выполняется.');await sourceStatus();}catch(e){toast(e.message);}}
async function exportData(){try{let data=await api('/api/export');let blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});let url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='aktivist-backup-'+new Date().toISOString().slice(0,10)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}catch(e){toast(e.message);}}
function bind(){ $$('.tab').forEach(tab=>tab.onclick=()=>{$$('.tab').forEach(t=>t.classList.toggle('active',t===tab));$$('.tab-pane').forEach(p=>p.classList.toggle('active',p.dataset.pane===tab.dataset.tab));});
$('#startDrawBtn').onclick=startDrawing;$('#cancelDrawBtn').onclick=stopDrawing;$('#finishDrawBtn').onclick=finishDrawing;$('#scanBtn').onclick=scan;$('#exportBtn').onclick=exportData;$('#detailBackdrop').onclick=closeDrawer;
$('#filter').onchange=renderEvents;$('#search').oninput=renderEvents;
$('#eventList').addEventListener('click',e=>{const card=e.target.closest('[data-event]');if(card)selectEvent(card.dataset.event);});
$('#eventList').addEventListener('keydown',e=>{const card=e.target.closest('[data-event]');if(card && (e.key==='Enter'||e.key===' ')){e.preventDefault();selectEvent(card.dataset.event);}});
$('#areaList').addEventListener('click',async e=>{
 const del=e.target.closest('[data-remove]'),focus=e.target.closest('[data-focus]'),
   osm=e.target.closest('[data-streets]'),cad=e.target.closest('[data-parcels]');
 if(osm){const id=osm.dataset.streets;
    if(!confirm('Загрузить геометрию и названия улиц OpenStreetMap для этой зоны через Overpass?'))return;
    osm.disabled=true;toast('Запрос улиц отправлен в Overpass...');
    try{const r=await api('/api/areas/'+encodeURIComponent(id)+'/streets','POST',{});
      await refresh();toast('В зоне найдено '+r.ways+' участков именованных дорог OSM.');}
    catch(error){toast('Справочник улиц: '+error.message);}finally{osm.disabled=false;}
 }else if(cad){parcelAreaId=cad.dataset.parcels;$('#parcelImportFile').value='';$('#parcelImportFile').click();}
 else if(del){if(!confirm('Удалить полигон наблюдения? Сами события останутся.'))return;
    try{await api('/api/areas/'+encodeURIComponent(del.dataset.remove),'DELETE');await refresh();}catch(error){toast(error.message);}}
 else if(focus&&map){let area=areas.find(a=>a.id===focus.dataset.focus);
    if(area){let layer=L.geoJSON({type:'Feature',geometry:area.geometry});map.fitBounds(layer.getBounds(),{padding:[35,35]});}}
});
$('#parcelImportFile').addEventListener('change',async e=>{
 const file=e.target.files?.[0];if(!file||!parcelAreaId)return;
 if(file.size>5_000_000){toast('Файл должен быть меньше 5 МБ');return;}
 try{
   let text=await file.text(),payload,records;
   if(/\.(geojson|json)$/i.test(file.name)){
     const j=JSON.parse(text);records=Array.isArray(j)?j:(j.type==='FeatureCollection'?j.features:j.parcels);
     if(!Array.isArray(records))throw Error('Ожидается GeoJSON FeatureCollection или массив parcels');
   }else{
     records=[...new Set(text.match(/\b\d{2}:\d{2}:\d{6,7}:\d{1,12}\b/g)||[])].map(cadastralNumber=>({cadastralNumber}));
   }
   if(!records.length)throw Error('Не найдены участки или кадастровые номера');
   let accepted=0,skipped=0;
   for(let i=0;i<records.length;i+=40){
     const slice=records.slice(i,i+40),geofeatures=slice.every(x=>x.type==='Feature');
     payload=geofeatures?{type:'FeatureCollection',features:slice,source:file.name}:{parcels:slice,source:file.name};
     const r=await api('/api/areas/'+encodeURIComponent(parcelAreaId)+'/parcels','POST',payload);
     accepted+=r.accepted;skipped+=r.skipped;
   }
   await refresh();toast('Импортировано участков: '+accepted+'; пропущено: '+skipped+'.');
 }catch(error){toast('Импорт участков: '+error.message);}
});
$('#addEventForm').onsubmit=async e=>{e.preventDefault();try{await api('/api/events','POST',{title:$('#eventTitle').value,url:$('#eventUrl').value,description:$('#eventDescription').value,source:'MANUAL'});$('#addEventForm').reset();await refresh();toast('Добавлено в ленту без точной геопривязки.');}catch(error){toast(error.message);}};
}
async function main(){initMap();bind();await refresh();await sourceStatus();setInterval(async()=>{await sourceStatus();if(!sourceBusy)await refresh();},15000);}
main().catch(e=>toast(e.message));
