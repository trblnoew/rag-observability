import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./e2e',testMatch:'demo.spec.ts',workers:1,timeout:30000,use:{baseURL:'http://localhost:5173',channel:'msedge',trace:'retain-on-failure'},webServer:{command:'node node_modules/vite/bin/vite.js --host localhost',url:'http://localhost:5173',reuseExistingServer:false}});
