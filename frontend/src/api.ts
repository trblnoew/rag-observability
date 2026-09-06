import type {Envelope} from './types';
export class ApiError extends Error {constructor(public code:string,message:string,public traceId:string|null=null,public uncertain=false){super(message)}}
export async function request<T>(path:string, init:RequestInit={}, signal?:AbortSignal):Promise<Envelope<T>> {
 const controller=new AbortController();const abort=()=>controller.abort();signal?.addEventListener('abort',abort,{once:true});
 if(signal?.aborted)controller.abort();
 const timer=setTimeout(()=>controller.abort(),600_000);
 try {
  const response=await fetch(path,{...init,signal:controller.signal});
  const traceId=response.headers.get('X-Trace-Id');
  const body=response.status===204?null:await response.json().catch(()=>null);
  if(!response.ok)throw new ApiError(body?.code??'HTTP_ERROR',body?.detail??'요청을 처리하지 못했습니다.',traceId);
  if(response.status!==204 && (body===null||typeof body!=='object'))throw new ApiError('INVALID_RESPONSE','서버 응답 형식을 확인할 수 없습니다. 현재 상태를 다시 조회하세요.',traceId,true);
  return {data:body as T,traceId};
 }catch(e){if(e instanceof ApiError)throw e;throw new ApiError('CONNECTION_FAILED','연결이 끊겼거나 응답을 기다리는 시간이 초과됐습니다. 처리 요청은 자동 재시도하지 않습니다.',null,true)}
 finally{clearTimeout(timer);signal?.removeEventListener('abort',abort)}
}
export function json(method:string,body:unknown):RequestInit{return {method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}}
export function normalizedQuestion(query:string,k:number,threshold:number) {
 const text=query.trim();if(!text||text.length>2000||!Number.isInteger(k)||k<1||k>20||!Number.isFinite(threshold)||threshold< -1||threshold>1)throw new Error('질문 1~2,000자, Top-K 1~20, similarity -1~1을 입력하세요.');
 return text;
}
