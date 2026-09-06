import {afterEach,describe,expect,it,vi} from 'vitest';
import {request,json,normalizedQuestion} from './api';
import experiments from './data/experiments.json';
afterEach(()=>vi.unstubAllGlobals());
describe('API contract',()=>{
 it('normalizes only surrounding whitespace and preserves request options',()=>{
  expect(normalizedQuestion('  a\n b  ',5,0)).toBe('a\n b');
  expect(json('POST',{query:'q',topK:5,minimumSimilarity:0})).toEqual({method:'POST',headers:{'Content-Type':'application/json'},body:'{"query":"q","topK":5,"minimumSimilarity":0}'});
 });
 it.each([['',5,0],['x'.repeat(2001),5,0],['q',0,0],['q',21,0],['q',1.5,0],['q',5,NaN],['q',5,Infinity],['q',5,1.1]])('rejects invalid input %s',(q,k,t)=>expect(()=>normalizedQuestion(q as string,k as number,t as number)).toThrow());
 it('preserves trace header and 204 deletion',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response('{"ok":true}',{headers:{'x-trace-id':'abc'}})).mockResolvedValueOnce(new Response(null,{status:204})));
  expect(await request('/api/test')).toEqual({data:{ok:true},traceId:'abc'});
  expect((await request('/api/test',{method:'DELETE'})).data).toBeNull();
 });
 it.each([[503,'EMBEDDING_DISABLED'],[409,'DOCUMENT_NOT_PROCESSABLE'],[502,'GENERATION_PROVIDER_FAILED']])('preserves safe problem %s',async(status,code)=>{
  const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify({code,detail:'Safe detail'}),{status:status as number}));vi.stubGlobal('fetch',fetcher);
  await expect(request('/api/test',{method:'POST'})).rejects.toMatchObject({code,message:'Safe detail'});expect(fetcher).toHaveBeenCalledTimes(1);
 });
 it('does not retry an uncertain network failure',async()=>{
  const fetcher=vi.fn().mockRejectedValue(new Error('private network detail'));vi.stubGlobal('fetch',fetcher);
  await expect(request('/api/test',{method:'POST'})).rejects.toMatchObject({code:'CONNECTION_FAILED',uncertain:true});expect(fetcher).toHaveBeenCalledTimes(1);
 });
 it('rejects invalid successful JSON safely',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('<html>private error</html>')));
  await expect(request('/api/test')).rejects.toMatchObject({code:'INVALID_RESPONSE'});
 });
 it('ships three measured datasets with 18 frozen questions and no private export fields',()=>{
  expect(experiments.map(e=>e.kind)).toEqual(['threshold','topk','chunk']);
  for(const e of experiments){expect(e.count).toBe(18);expect(e.queries).toHaveLength(e.rows.length*18);expect(new Set(e.queries.map(q=>q.id)).size).toBe(18);expect(e.datasetHash).toMatch(/^[a-f0-9]{64}$/)}
  expect(JSON.stringify(experiments)).not.toMatch(/C:\\\\Users|jdbc:|api.key|containerId|"embedding"|"content"/i);
  expect(experiments.find(e=>e.kind==='topk')!.rows.map(r=>r.hit)).toEqual([10/12,1,1,1]);
 });
});
