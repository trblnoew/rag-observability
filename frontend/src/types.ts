export type Mode='demo'|'live';
export type Status='PENDING'|'PROCESSING'|'READY'|'FAILED';
export interface DocumentSummary {id:number;title:string;originalFilename:string;sourceType:string;status:Status;embeddingStatus:'NOT_STARTED'|Status;retryCount:number;createdAt:string;updatedAt:string}
export interface DocumentDetail extends DocumentSummary {content:string;contentSha256:string;errorCode:string|null;errorMessage:string|null;embeddingErrorCode:string|null;embeddingErrorMessage:string|null;embeddingRetryCount:number}
export interface Chunk {id:number;chunkIndex:number;content:string;tokenCount:number;embedded:boolean;embeddingProvider:string|null;embeddingModel:string|null;createdAt:string}
export interface DocumentPage {items:DocumentSummary[];page:number;size:number;totalElements:number;totalPages:number}
export interface Hit {chunkId:number;documentId:number;documentTitle:string;chunkIndex:number;similarity:number;content:string}
export interface Source extends Omit<Hit,'content'> {sourceId:string;excerpt:string}
export interface Meta {topK:number;minimumSimilarity:number;embeddingProvider:string;embeddingModel:string;dimensions:number;generationProvider?:string;generationModel?:string;generationCalled?:boolean;inputTokens?:number|null;outputTokens?:number|null}
export interface SearchResponse {query:string;results:Hit[];meta:Meta}
export interface AnswerResponse {question:string;answer:string;sources:Source[];meta:Meta}
export interface Envelope<T> {data:T;traceId:string|null}
export interface ExperimentRow {condition:number;hit:number;mrr:number;negativeReturn:number;hardReturn:number|null;unrelatedReturn:number|null;meanChunks:number;directHit:number;paraphrasedHit:number;meanReturnedTokens?:number;chunkCount?:number}
export interface ExperimentQuery {id:string;category:string;question:string;expected:string|null;reason:string|null;condition:number;rank:number|null;hit:boolean|null;resultCount:number}
export interface Experiment {kind:string;title:string;fixed:string;runId:string;startedAt:string;datasetId:string;datasetHash:string;model:string;count:number;rows:ExperimentRow[];queries:ExperimentQuery[]}
