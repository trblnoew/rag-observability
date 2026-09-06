import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./e2e',testMatch:'live.spec.ts',outputDir:'test-results-live',workers:1,timeout:90000,use:{baseURL:'http://localhost:5174',channel:'msedge',trace:'retain-on-failure'},webServer:{command:'node node_modules/vite/bin/vite.js --host localhost --port 5174',url:'http://localhost:5174',reuseExistingServer:false}});
