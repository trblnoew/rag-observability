import {test,expect} from '@playwright/test';
test('keyboard navigation and submit remain accessible',async({page})=>{
 await page.goto('/');const link=page.getByRole('link',{name:/검색 · 질문/});await link.focus();await page.keyboard.press('Enter');
 const submit=page.getByRole('button',{name:'근거 검색하기 →'});await submit.focus();await page.keyboard.press('Enter');await expect(page.getByRole('heading',{name:'검색 결과',exact:true})).toBeVisible();
 await page.getByRole('link',{name:/실험 결과/}).focus();await page.keyboard.press('Enter');await page.getByRole('region',{name:'실험 수치 표'}).focus();await expect(page.getByRole('region',{name:'실험 수치 표'})).toBeFocused();
});
for(const width of [1440,768,375])test(`demo three screens at ${width}px without API calls`,async({page})=>{
 let calls=0;page.on('request',r=>{if(/\/api\/|\/actuator\//.test(r.url()))calls++});
 await page.setViewportSize({width,height:950});await page.goto('/');
 await expect(page.getByRole('button',{name:'문서 업로드',exact:true})).toBeDisabled();
 await page.getByRole('button',{name:/db-connection-timeout/}).click();
 await expect(page.getByRole('button',{name:/Chunks ·/})).toBeVisible();
 await page.getByRole('button',{name:'원문',exact:true}).click();await expect(page.locator('pre')).toContainText('HikariCP');
 await page.getByRole('link',{name:/검색 · 질문/}).click();
 await page.getByRole('button',{name:'근거 검색하기 →'}).click();await expect(page.getByRole('heading',{name:'검색 결과',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'답변 생성',exact:true}).click();await page.getByRole('button',{name:'근거 기반 답변 생성 →'}).click();
 await page.getByRole('button',{name:'[S1]',exact:true}).click();await expect(page.locator('#source-S1')).toBeVisible();
 await page.getByLabel('예시 시나리오').selectOption({label:'연차 신청은 누구에게 승인받아야 하나요?'});
 await page.getByRole('button',{name:'근거 기반 답변 생성 →'}).click();await expect(page.getByText('근거 부족 · Generation을 호출하지 않았습니다.')).toBeVisible();
 await page.getByRole('link',{name:/실험 결과/}).click();
 for(const name of ['Threshold','Top-K','Chunk Size']){await page.getByRole('button',{name,exact:true}).click();await expect(page.getByRole('table')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)}
 await page.screenshot({path:`test-results/experiments-${width}.png`,fullPage:true});expect(calls).toBe(0);
});
for(const [status,code] of [[503,'GENERATION_DISABLED'],[409,'DOCUMENT_NOT_PROCESSABLE'],[502,'GENERATION_PROVIDER_FAILED']] as const)test(`live problem ${status} does not fall back to demo`,async({page})=>{
 await page.route('**/api/v1/answers',r=>r.fulfill({status,contentType:'application/problem+json',body:JSON.stringify({code,detail:'Safe failure'})}));
 await page.goto('/#ask');await page.getByLabel('연결 모드').selectOption('live');await page.getByRole('button',{name:'답변 생성',exact:true}).click();
 await page.getByRole('button',{name:'근거 기반 답변 생성 →'}).click();await expect(page.getByRole('alert')).toContainText(code);
 await expect(page.getByLabel('연결 모드')).toHaveValue('live');await expect(page.getByRole('heading',{name:'답변',exact:true})).toHaveCount(0);
});
test('single request, safe text, citations, trace and mode reset',async({page})=>{
 let count=0;await page.route('**/api/v1/answers',async r=>{count++;expect(r.request().postDataJSON()).toEqual({question:'test',topK:5,minimumSimilarity:.5});await new Promise(resolve=>setTimeout(resolve,200));await r.fulfill({contentType:'application/json',headers:{'X-Trace-Id':'test-trace'},body:JSON.stringify({question:'test',answer:'<img src=x onerror=alert(1)> [S1]',sources:[{sourceId:'S1',documentId:1,chunkId:1,documentTitle:'safe',chunkIndex:0,similarity:.8,excerpt:'<script>alert(1)</script>'}],meta:{topK:5,minimumSimilarity:.5,embeddingProvider:'fake',embeddingModel:'fake',dimensions:1536,generationCalled:true,generationProvider:'fake',generationModel:'fake',inputTokens:2,outputTokens:1}})})});
 await page.goto('/#ask');await page.getByLabel('연결 모드').selectOption('live');await page.getByRole('button',{name:'답변 생성',exact:true}).click();await page.getByLabel('질문',{exact:true}).fill(' test ');
 await page.getByRole('button',{name:'근거 기반 답변 생성 →'}).evaluate((b:HTMLButtonElement)=>{b.click();b.click()});
 await page.getByRole('button',{name:'[S1]',exact:true}).click();expect(count).toBe(1);await expect(page.locator('#source-S1')).toContainText('<script>');await expect(page.locator('main img')).toHaveCount(0);
 await page.getByText('요청 추적 · Trace ID 있음').click();await expect(page.getByText('test-trace',{exact:true})).toBeVisible();
 await page.getByLabel('연결 모드').selectOption('demo');await expect(page.getByRole('heading',{name:'답변',exact:true})).toHaveCount(0);
});
