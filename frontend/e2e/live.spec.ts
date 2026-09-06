import {test,expect} from '@playwright/test';
test('browser → Vite proxy → Spring HTTP → isolated pgvector → Fake AI',async({page,request})=>{
 expect(process.env.FRONTEND_TEST_BACKEND_PORT,'Run through the Java isolated harness').toBeTruthy();
 const health=await request.get('/actuator/health');expect(health.status()).toBe(200);expect((await health.json()).status).toBe('UP');
 page.on('dialog',d=>d.accept());await page.goto('/');await page.getByLabel('연결 모드').selectOption('live');
 await page.locator('input[type=file]').setInputFiles({name:'browser-flow.txt',mimeType:'text/plain',buffer:Buffer.from('database timeout recovery guide')});
 await page.getByRole('button',{name:'문서 업로드',exact:true}).click();await page.getByRole('button',{name:/browser-flow.*browser-flow.txt/}).click();
 await page.getByRole('button',{name:'Chunking 실행',exact:true}).click();await expect(page.getByRole('button',{name:'Embedding 실행',exact:true})).toBeEnabled();
 await page.getByRole('button',{name:'Embedding 실행',exact:true}).click();await expect(page.getByText('Embedding · READY',{exact:true})).toBeVisible();
 await page.getByLabel('문서 제목 수정').fill('Browser verified');await page.getByRole('button',{name:'제목 저장'}).click();await expect(page.getByRole('heading',{name:'Browser verified'})).toBeVisible();
 await page.getByRole('link',{name:'02 검색 · 질문'}).click();await page.getByLabel('질문',{exact:true}).fill('database timeout');
 await page.getByRole('button',{name:'근거 검색하기 →'}).click();await expect(page.getByText('1. Browser verified')).toBeVisible();
 await page.getByRole('button',{name:'답변 생성',exact:true}).click();await page.getByRole('button',{name:'근거 기반 답변 생성 →'}).click();await expect(page.getByText('Check the pool',{exact:false})).toBeVisible();
 await page.getByRole('button',{name:'[S1]',exact:true}).click();await page.getByRole('link',{name:'문서 보기',exact:true}).click();
 await page.getByRole('button',{name:'삭제',exact:true}).click();await expect(page.getByText('등록된 문서가 없습니다.')).toBeVisible();
});
