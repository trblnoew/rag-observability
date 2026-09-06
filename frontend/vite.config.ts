import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
// The optional port is used only by the isolated browser integration test. No host override.
const testPort=process.env.FRONTEND_TEST_BACKEND_PORT;
if(testPort && (!/^\d+$/.test(testPort)||Number(testPort)<1||Number(testPort)>65535))throw new Error('Invalid test backend port');
const backend='http://localhost:'+(testPort??'8080');
export default defineConfig({ plugins:[react()], server:{host:'localhost',port:5173,strictPort:true,proxy:{'/api':{target:backend},'^/actuator/health(?:$|\\?)':{target:backend}}} });
