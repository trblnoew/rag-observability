import {useEffect,useRef,useState} from 'react';
import type {Mode,DocumentDetail,DocumentPage,Chunk,SearchResponse,AnswerResponse,Experiment} from './types';
import {request,json,ApiError,normalizedQuestion} from './api';
import rawDocuments from './data/demo-documents.json';
import rawExperiments from './data/experiments.json';
import styles from './App.module.css';

const docs=rawDocuments as (DocumentDetail&{chunks:Chunk[]})[];
const experiments=rawExperiments as Experiment[];
type Route='documents'|'ask'|'experiments';
function route():Route {const name=location.hash.slice(1).split('/')[0];return name==='ask'||name==='experiments'?name:'documents'}
function Badge({children}:{children:React.ReactNode}){return <span className={styles.badge}>{children}</span>}
function Trace({id}:{id:string|null}) {
 const [copied,setCopied]=useState(false);
 return <details className={styles.trace}><summary>요청 추적 · {id?'Trace ID 있음':'수집 정보 없음'}</summary>
 {id&&<><code>{id}</code><button onClick={()=>navigator.clipboard.writeText(id).then(()=>setCopied(true)).catch(()=>setCopied(false))}>{copied?'복사됨':'Trace ID 복사'}</button></>}
 <p>Grafana Explore → Tempo에서 Trace ID를 검색하세요. 샘플링·전송·보존 기간에 따라 결과가 없을 수 있습니다.</p></details>
}
function ErrorBox({error}:{error:Error|null}) {return error?<div className={styles.error} role="alert"><strong>{error instanceof ApiError?error.code:'입력 확인'}</strong><p>{error.message}</p>{error instanceof ApiError&&<Trace id={error.traceId}/>}</div>:null}
export default function App(){
 const [mode,setMode]=useState<Mode>('demo');const [page,setPage]=useState<Route>(route);
 useEffect(()=>{const change=()=>setPage(route());window.addEventListener('hashchange',change);return()=>window.removeEventListener('hashchange',change)},[]);
 return <div className={styles.app}>
 <aside className={styles.sidebar}><a className={styles.brand} href="#documents"><span className={styles.logo}>R</span>RAG Observatory</a>
 <div className={styles.navLabel}>WORKSPACE</div><nav aria-label="주 메뉴">{([['documents','01','문서 라이브러리'],['ask','02','검색 · 질문'],['experiments','03','실험 결과']] as const).map(([key,n,label])=><a href={'#'+key} key={key} aria-current={page===key?'page':undefined}><span>{n}</span>{label}</a>)}</nav>
 <div className={styles.sidebarFooter}><span className={styles.dot}/> Spring Boot + pgvector<br/><small>근거를 찾고, 결과를 검증합니다.</small></div></aside>
 <div className={styles.workspace}><header className={styles.topbar}><span>문서 기반 AI · 관측 가능한 검색</span><label>연결 모드 <select aria-label="연결 모드" value={mode} onChange={e=>setMode(e.target.value as Mode)}><option value="demo">무료 데모</option><option value="live">로컬 API 연결</option></select></label></header>
 <main key={mode+page}><div className={styles.modeNotice}>{mode==='demo'?'무료 데모 · DB와 API Key 없이 탐색합니다. 문서 상태와 답변은 설명용입니다.':'로컬 연결 · 서버 localhost:8080 · 검색/Embedding/답변은 API 비용이 발생할 수 있습니다.'}</div>
 {page==='documents'?<Documents mode={mode}/>:page==='ask'?<Ask mode={mode}/>:<Experiments/>}
 </main><footer className={styles.footer}>RAG Observatory · 로컬 포트폴리오 데모 <span>검색 성공 ≠ 답변 정확성</span></footer></div>
 </div>
}
function Documents({mode}:{mode:Mode}){
 const initial=Number(location.hash.split('/')[1])||null;
 const [items,setItems]=useState<DocumentPage>({items:[],page:0,size:20,totalElements:0,totalPages:0});
 const [page,setPage]=useState(0);const [filter,setFilter]=useState('');const [selected,setSelected]=useState<number|null>(initial);
 const [detail,setDetail]=useState<DocumentDetail|null>(null);const [chunks,setChunks]=useState<Chunk[]>([]);
 const [tab,setTab]=useState<'chunks'|'content'>('chunks');const [title,setTitle]=useState('');
 const [busy,setBusy]=useState(false);const [loading,setLoading]=useState(false);const [error,setError]=useState<Error|null>(null);
 const [notice,setNotice]=useState('');const [uploadTitle,setUploadTitle]=useState('');const file=useRef<HTMLInputElement>(null);
 const lock=useRef(false);const active=useRef(true);const detailVersion=useRef(0);
 useEffect(()=>()=>{active.current=false},[]);
 async function refresh(id=selected){
  if(mode==='demo'){setItems({items:docs.filter(d=>!filter||d.status===filter),page:0,size:20,totalElements:docs.filter(d=>!filter||d.status===filter).length,totalPages:1});return}
  const res=await request<DocumentPage>('/api/v1/documents?page='+page+'&size=20'+(filter?'&status='+filter:''));
  if(!active.current)return;setItems(res.data);
  if(id)await loadDetail(id);
 }
 async function loadDetail(id:number){
  const version=++detailVersion.current;setDetail(null);setChunks([]);
  if(mode==='demo'){const doc=docs.find(d=>d.id===id);if(doc){setDetail(doc);setTitle(doc.title);setChunks(doc.chunks)}return}
  const [d,c]=await Promise.all([request<DocumentDetail>('/api/v1/documents/'+id),request<{items:Chunk[]}>('/api/v1/documents/'+id+'/chunks')]);
  if(active.current&&version===detailVersion.current){setDetail(d.data);setTitle(d.data.title);setChunks(c.data.items)}
 }
 useEffect(()=>{setError(null);setLoading(true);refresh().catch(e=>active.current&&setError(e)).finally(()=>active.current&&setLoading(false))},[page,filter]);
 useEffect(()=>{if(selected)loadDetail(selected).catch(e=>active.current&&setError(e));else{detailVersion.current++;setDetail(null);setChunks([])}},[selected]);
 useEffect(()=>{const changed=()=>{if(route()==='documents'){const id=Number(location.hash.split('/')[1]);if(id)setSelected(id)}};window.addEventListener('hashchange',changed);return()=>window.removeEventListener('hashchange',changed)},[]);
 async function mutate(path:string,init:RequestInit,deleted=false){
  if(lock.current||mode==='demo')return;lock.current=true;setBusy(true);setError(null);setNotice('');
  try{await request(path,init);if(active.current){if(deleted){setSelected(null);setDetail(null)}await refresh(deleted?null:selected);setNotice('처리가 완료됐습니다.')}}catch(e){
   if(active.current){setError(e as Error);try{await refresh()}catch{setNotice('현재 상태도 확인하지 못했습니다. 연결 복구 후 새로고침하세요.')} }
  }finally{lock.current=false;if(active.current)setBusy(false)}
 }
 async function upload(e:React.FormEvent){e.preventDefault();const f=file.current?.files?.[0];if(!f)return;
  if(!/\.(txt|md)$/i.test(f.name)||f.size>1024*1024||f.size===0){setError(new Error('빈 파일이 아닌 TXT/Markdown 파일, 최대 1MB만 업로드할 수 있습니다.'));return}
  const body=new FormData();body.append('file',f);if(uploadTitle.trim())body.append('title',uploadTitle.trim());await mutate('/api/v1/documents',{method:'POST',body});
 }
 return <><div className={styles.heading}><div><span className={styles.eyebrow}>DOCUMENT PIPELINE</span><h1>문서 라이브러리</h1><p>원문에서 Chunk, 검색 가능한 벡터까지.</p></div><Badge>{items.totalElements} documents</Badge></div>
 <div className={styles.pipeline}><span>01 원문 업로드</span><span>→</span><span>02 Chunking</span><span>→</span><span>03 Embedding</span><span>→ 검색 준비</span></div>
 <ErrorBox error={error}/>{notice&&<p role="status">{notice}</p>}
 <form className={styles.upload} onSubmit={upload}><label>TXT / Markdown · 최대 1MB<input type="file" ref={file} accept=".txt,.md" disabled={mode==='demo'||busy}/></label>
 <label>제목 (선택)<input value={uploadTitle} maxLength={255} onChange={e=>setUploadTitle(e.target.value)} disabled={mode==='demo'||busy} placeholder="파일명으로 자동 생성"/></label>
 <button className={styles.primary} disabled={mode==='demo'||busy}>문서 업로드</button></form>
 <div className={styles.documentLayout}><section className={styles.documentList} aria-label="문서 목록"><div className={styles.sectionBar}><h2>내 문서</h2>
 <select aria-label="Chunking 상태 필터" value={filter} disabled={busy} onChange={e=>{setPage(0);setFilter(e.target.value)}}><option value="">모든 상태</option>{['PENDING','PROCESSING','READY','FAILED'].map(s=><option key={s}>{s}</option>)}</select></div>
 {loading&&<p role="status">불러오는 중…</p>}{!loading&&items.items.length===0&&<p>등록된 문서가 없습니다.</p>}
 {items.items.map(d=><button className={styles.documentItem} key={d.id} aria-pressed={selected===d.id} disabled={busy} onClick={()=>{setSelected(d.id);location.hash='documents/'+d.id}}>
 <span className={styles.fileIcon}>MD</span><span><strong>{d.title}</strong><small>{d.originalFilename}</small><span className={styles.statusRow}>Chunk {d.status} · Vector {d.embeddingStatus}</span></span></button>)}
 <div className={styles.sectionBar}><button disabled={page===0||busy||mode==='demo'} onClick={()=>setPage(page-1)}>이전</button><span>{page+1} / {Math.max(1,items.totalPages)}</span><button disabled={page+1>=items.totalPages||busy||mode==='demo'} onClick={()=>setPage(page+1)}>다음</button></div>
 <button disabled={busy} onClick={()=>{setError(null);refresh().catch(setError)}}>상태 새로고침</button></section>
 <section className={styles.detail}>{!detail?<div className={styles.empty}><h2>문서를 선택하세요</h2><p>원문과 분할된 Chunk를 함께 살펴볼 수 있습니다.</p></div>:<>
 <div className={styles.sectionBar}><h2>{detail.title}</h2><Badge>{detail.sourceType}</Badge></div>
 <div className={styles.statusRow}><Badge>Chunking · {detail.status}</Badge><Badge>Embedding · {detail.embeddingStatus}</Badge></div>
 <p className={styles.muted}>Chunk {chunks.length}개 · 분할 재시도 {detail.retryCount} · Embedding 재시도 {detail.embeddingRetryCount}</p>
 {(detail.errorCode||detail.embeddingErrorCode)&&<p role="alert">{detail.errorCode??detail.embeddingErrorCode}: {detail.errorMessage??detail.embeddingErrorMessage}</p>}
 <div className={styles.actions}><button disabled={mode==='demo'||busy||!['PENDING','FAILED'].includes(detail.status)} onClick={()=>mutate('/api/v1/documents/'+detail.id+'/process',{method:'POST'})}>Chunking 실행</button>
 <button disabled={mode==='demo'||busy||detail.status!=='READY'||!['PENDING','FAILED'].includes(detail.embeddingStatus)} onClick={()=>{if(confirm('Embedding API 비용이 발생합니다. 실행할까요?'))mutate('/api/v1/documents/'+detail.id+'/embeddings',{method:'POST'})}}>Embedding 실행</button>
 <button disabled={mode==='demo'||busy} onClick={()=>{if(confirm('문서와 연결된 Chunk를 영구 삭제합니다. 계속할까요?'))mutate('/api/v1/documents/'+detail.id,{method:'DELETE'},true)}}>삭제</button></div>
 <div className={styles.actions}><input aria-label="문서 제목 수정" value={title} maxLength={255} disabled={mode==='demo'||busy} onChange={e=>setTitle(e.target.value)}/><button disabled={mode==='demo'||busy||!title.trim()} onClick={()=>mutate('/api/v1/documents/'+detail.id,json('PATCH',{title:title.trim()}))}>제목 저장</button></div>
 {busy&&<p role="status">처리 중… 화면을 닫아도 서버 작업은 취소되지 않습니다.</p>}
 <div className={styles.tabs}><button aria-pressed={tab==='chunks'} onClick={()=>setTab('chunks')}>Chunks · {chunks.length}</button><button aria-pressed={tab==='content'} onClick={()=>setTab('content')}>원문</button></div>
 {tab==='content'?<pre>{detail.content}</pre>:chunks.length===0?<p>아직 Chunk가 없습니다.</p>:chunks.map(c=><article key={c.id} className={styles.chunk}><div className={styles.sectionBar}><strong>Chunk {c.chunkIndex}</strong><small>{c.tokenCount} tokens · {c.embedded?'Embedded':'미처리'}</small></div><p>{c.content}</p><small>{c.embeddingProvider??'—'} / {c.embeddingModel??'—'}</small></article>)}
 </>}</section></div></>
}
function Ask({mode}:{mode:Mode}){
 const [kind,setKind]=useState<'search'|'answer'>('search');const [query,setQuery]=useState('HikariCP connection timeout이 발생하면 무엇을 확인해야 하나요?');
 const [k,setK]=useState(5);const [threshold,setThreshold]=useState(0);const [busy,setBusy]=useState(false);const lock=useRef(false);const active=useRef(true);
 const [result,setResult]=useState<SearchResponse|AnswerResponse|null>(null);const [error,setError]=useState<Error|null>(null);const [trace,setTrace]=useState<string|null>(null);const [source,setSource]=useState('');
 useEffect(()=>()=>{active.current=false},[]);
 function changeKind(value:'search'|'answer'){setKind(value);setThreshold(value==='search'?0:.5);setResult(null);setError(null);setTrace(null)}
 async function submit(e:React.FormEvent){e.preventDefault();if(lock.current)return;setError(null);
  try{
   const text=normalizedQuestion(query,k,threshold);lock.current=true;setBusy(true);setResult(null);setTrace(null);setSource('');
   if(mode==='demo'){
    const cpu=text.includes('CPU');const unrelated=text.includes('연차');const doc=docs.find(d=>d.originalFilename===(cpu?'cpu-spike.md':'db-connection-timeout.md'))!;
    const meta={topK:k,minimumSimilarity:threshold,embeddingProvider:'demo',embeddingModel:'illustration',dimensions:1536};
    const hit={chunkId:doc.chunks[0].id,documentId:doc.id,documentTitle:doc.title,chunkIndex:0,similarity:.82,content:doc.chunks[0].content};
    if(kind==='search')setResult({query:text,results:unrelated||threshold>.82?[]:[hit],meta});
    else setResult({question:text,answer:unrelated||threshold>.82?'제공된 문서만으로는 확인할 수 없습니다.':cpu?'CPU 제한과 throttling, GC, 스레드 상태를 확인하세요. [S1]':'HikariCP 풀 사용량과 PostgreSQL 연결 제한, 장기 트랜잭션을 확인하세요. [S1]',sources:unrelated||threshold>.82?[]:[{...hit,sourceId:'S1',excerpt:hit.content.slice(0,500)}],meta:{...meta,generationCalled:!unrelated&&threshold<=.82,generationProvider:'demo',generationModel:'illustration',inputTokens:null,outputTokens:null}});
   }else {
    const response=await request<SearchResponse|AnswerResponse>(kind==='search'?'/api/v1/search':'/api/v1/answers',json('POST',kind==='search'?{query:text,topK:k,minimumSimilarity:threshold}:{question:text,topK:k,minimumSimilarity:threshold}));
    if(active.current){setResult(response.data);setTrace(response.traceId)}
   }
  }catch(e){if(active.current)setError(e as Error)}finally{lock.current=false;if(active.current)setBusy(false)}
 }
 const answer=result&&'answer'in result?result:null;
 return <><div className={styles.heading}><div><span className={styles.eyebrow}>EVIDENCE FIRST</span><h1>근거에서 시작하는 질문</h1><p>검색 결과와 생성된 답변을 구분해서 확인하세요.</p></div></div>
 <div className={styles.askLayout}><section><form onSubmit={submit} className={styles.askForm}><div className={styles.tabs}><button type="button" disabled={busy} aria-pressed={kind==='search'} onClick={()=>changeKind('search')}>검색만</button><button type="button" disabled={busy} aria-pressed={kind==='answer'} onClick={()=>changeKind('answer')}>답변 생성</button></div>
 <label>질문<textarea aria-label="질문" value={query} maxLength={2000} rows={5} disabled={busy||mode==='demo'} onChange={e=>setQuery(e.target.value)}/></label>
 {mode==='demo'&&<label>예시 시나리오<select aria-label="예시 시나리오" value={query} onChange={e=>{setQuery(e.target.value);setResult(null)}}><option>HikariCP connection timeout이 발생하면 무엇을 확인해야 하나요?</option><option>컨테이너 CPU 사용률이 급증하면 무엇을 확인하나요?</option><option>연차 신청은 누구에게 승인받아야 하나요?</option></select></label>}
 <details><summary>고급 검색 옵션 · Top-K {k} / similarity {threshold}</summary><div className={styles.actions}><label>Top-K<input type="number" min={1} max={20} value={k} disabled={busy} onChange={e=>setK(e.target.valueAsNumber)}/></label><label>Minimum similarity<input type="number" min={-1} max={1} step=".1" value={threshold} disabled={busy} onChange={e=>setThreshold(e.target.valueAsNumber)}/></label></div><small>이번 요청에만 적용되며 서버 설정은 바뀌지 않습니다.</small></details>
 <p className={styles.muted}>{mode==='demo'?'설명용 예시, 실제 생성 결과 아님 · 검색 점수도 예시입니다.':kind==='search'?'실행 시 Query Embedding API 비용이 발생합니다.':'실행 시 Query Embedding 및 근거가 있으면 Generation API 비용이 발생합니다.'}</p>
 <button className={styles.primary} disabled={busy}>{busy?'처리 중…':kind==='search'?'근거 검색하기 →':'근거 기반 답변 생성 →'}</button></form>
 <ErrorBox error={error}/>
 {result&&<section className={styles.result}><div className={styles.sectionBar}><h2>{answer?'답변':'검색 결과'}</h2><Badge>{mode==='demo'?'설명용 예시':'실제 API 응답'}</Badge></div><p>실행한 질문: {'question' in result?result.question:result.query}</p>
 {answer?<><p className={styles.answer}>{answer.answer.split(/(\[S\d+\])/g).map((t,i)=>/^\[S\d+\]$/.test(t)&&answer.sources.some(s=>s.sourceId===t.slice(1,-1))?<button key={i} onClick={()=>{setSource(t.slice(1,-1));document.getElementById('source-'+t.slice(1,-1))?.scrollIntoView({block:'nearest'})}}>{t}</button>:t)}</p>
 {!answer.meta.generationCalled&&<p>근거 부족 · Generation을 호출하지 않았습니다.</p>}
 {answer.sources.map(s=><article id={'source-'+s.sourceId} key={s.sourceId} className={source===s.sourceId?styles.selectedSource:styles.chunk}><strong>{s.sourceId} · {s.documentTitle}</strong><p>{s.excerpt}</p><small>Chunk {s.chunkIndex} · similarity {s.similarity.toFixed(4)}</small> <a href={'#documents/'+s.documentId}>문서 보기</a></article>)}</>:
 'results'in result&&<>{result.results.length===0?<p>조건을 통과한 근거가 없습니다.</p>:result.results.map((h,i)=><article className={styles.chunk} key={h.chunkId}><div className={styles.sectionBar}><strong>{i+1}. {h.documentTitle}</strong><Badge>{h.similarity.toFixed(4)}</Badge></div><p>{h.content}</p><a href={'#documents/'+h.documentId}>문서 보기 · Chunk {h.chunkIndex}</a></article>)}</>}
 <details><summary>실제 응답 조건 · 모델 정보</summary><p>Top-K {result.meta.topK} · similarity {result.meta.minimumSimilarity}</p><p>{result.meta.embeddingProvider} / {result.meta.embeddingModel} / {result.meta.dimensions}차원</p>{answer&&<p>{answer.meta.generationProvider} / {answer.meta.generationModel} · 입력 {answer.meta.inputTokens??'미제공'} / 출력 {answer.meta.outputTokens??'미제공'} tokens</p>}</details><Trace id={trace}/></section>}
 </section><aside className={styles.guide}><span className={styles.eyebrow}>HOW IT WORKS</span><h2>답변 이전에,<br/>근거를 확인하세요.</h2><ol><li><strong>질문을 벡터로</strong><p>문서와 같은 모델의 의미 좌표를 만듭니다.</p></li><li><strong>가까운 Chunk 검색</strong><p>Cosine distance로 적격 문서를 비교합니다.</p></li><li><strong>근거로 답변 생성</strong><p>답변 모드에서만 LLM을 호출합니다.</p></li></ol><hr/><p>높은 similarity가 정답을 보장하지 않습니다. 출처가 주장을 실제로 뒷받침하는지 확인하세요.</p></aside></div></>
}
function Experiments(){
 const [kind,setKind]=useState('threshold');const run=experiments.find(e=>e.kind===kind)!;
 const [id,setId]=useState('V-DB-01');const rows=run.queries.filter(q=>q.id===id);const q=rows[0];
 return <><div className={styles.heading}><div><span className={styles.eyebrow}>MEASURE, DON'T GUESS</span><h1>실험으로 보는 검색 품질</h1><p>같은 질문, 다른 조건. 무엇을 얻고 무엇을 놓쳤을까요?</p></div><Badge>저장된 실측 결과 · API 호출 0회</Badge></div>
 <div className={styles.tabs}>{experiments.map(e=><button key={e.kind} aria-pressed={kind===e.kind} onClick={()=>setKind(e.kind)}>{e.title}</button>)}</div>
 <div className={styles.sectionBar}><h2>{run.title} 비교</h2><span>{run.fixed} · 질문 {run.count}개</span></div>
 <div className={styles.chart} role="img" aria-label={run.rows.map(r=>run.title+' '+r.condition+': 정답 검색 '+Math.round(r.hit*100)+'%, 부정 질문 반환 '+Math.round(r.negativeReturn*100)+'%').join('; ')}><div className={styles.legend}><span>● 정답 검색률 ↑</span><span>● 부정 질문 반환율 ↓</span></div>
 {run.rows.map(r=><div className={styles.barRow} key={r.condition}><strong>{r.condition}</strong><div><div className={styles.track}><div className={styles.bar} style={{width:r.hit*100+'%'}}/></div><div className={styles.track}><div className={styles.negativeBar} style={{width:r.negativeReturn*100+'%'}}/></div></div><span>{(r.hit*100).toFixed(1)}%<br/>{(r.negativeReturn*100).toFixed(1)}%</span></div>)}</div>
 <p className={styles.muted}>좁은 화면에서는 표를 좌우로 스크롤하세요.</p>
 <div className={styles.tableWrap} tabIndex={0} role="region" aria-label="실험 수치 표"><table><caption>정답 있는 질문 12개 · 답 없는 질문 6개 (어려운 부정 3 / 무관 3)</caption><thead><tr>{[run.title,kind==='topk'?'Hit@K':'Hit@5',kind==='topk'?'MRR@K':'MRR@5','직접 Hit','바꿔 말하기 Hit','어려운 부정 반환','무관 반환','평균 Chunk',...(kind==='chunk'?['전체 Chunk 수','평균 반환 tokens']:[])].map(c=><th key={c}>{c}</th>)}</tr></thead><tbody>{run.rows.map(r=><tr key={r.condition}><th>{r.condition}</th>{[r.hit,r.mrr,r.directHit,r.paraphrasedHit,r.hardReturn,r.unrelatedReturn,r.meanChunks,...(kind==='chunk'?[r.chunkCount,r.meanReturnedTokens]:[])].map((n,i)=><td key={i}>{n==null?'—':n.toFixed(4)}</td>)}</tr>)}</tbody></table></div>
 {kind==='chunk'&&<p className={styles.muted}>반환 tokens는 Chunk token 수의 합계이며 실제 LLM 사용량·과금액이 아닙니다.</p>}
 <div className={styles.insight}><strong>숫자 뒤의 해석</strong><p>{kind==='chunk'?'300은 정답 순위와 반환 token 양에서 유리했지만 무관한 제빵 질문도 통과했습니다. 세 크기 모두 어려운 부정 질문 3개를 걸러내지 못했습니다.':kind==='topk'?'K=2부터 정답 문서를 모두 찾았습니다. K=5와 문서 검색 성공률이 같아도 답변에 충분한 근거가 포함됐다는 뜻은 아닙니다.':'0.3은 필요한 문서를 보존하지만 답 없는 질문도 통과합니다. 0.5는 정답 문서를 많이 버리면서도 어려운 부정 질문 하나가 남습니다.'}</p></div>
 <section className={styles.result}><h2>질문별 채점표</h2><label>질문 선택<select value={id} onChange={e=>setId(e.target.value)}>{run.queries.filter((v,i,a)=>a.findIndex(w=>w.id===v.id)===i).map(q=><option key={q.id} value={q.id}>{q.id} · {q.question}</option>)}</select></label>
 {q&&<><h3>{q.question}</h3><p><Badge>{q.category}</Badge> 정답 문서: {q.expected??'없음'}</p><p>{q.reason}</p><div className={styles.actions}>{rows.map(r=><div className={styles.score} key={r.condition}><strong>{run.title} {r.condition}</strong><p>{r.hit==null?(r.resultCount?'답 없는 질문에 결과 반환':'결과 없음'):r.hit?'정답 '+r.rank+'위':r.resultCount?'다른 문서만 반환':'빈 결과'}</p><small>Chunk {r.resultCount}개</small></div>)}</div></>}
 </section><details className={styles.provenance}><summary>실측 출처 · 실행 조건</summary><p>{run.runId}</p><p>{run.startedAt} · {run.datasetId} · {run.model}</p><code>{run.datasetHash}</code><p>운영 기본값은 Chunk 600 / Top-K 5 / RAG threshold 0.5입니다. 이 화면은 설정을 변경하지 않습니다.</p><p>문서 수준 채점이며 LLM 답변 품질이나 지연시간을 비교하지 않습니다. 서로 다른 실험의 점수를 합산하지 않습니다.</p></details>
 </>}
