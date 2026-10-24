/* eslint-disable */
import type { DocumentTypeDecoration } from '@graphql-typed-document-node/core';
export type Maybe<T> = T | null;
export type InputMaybe<T> = Maybe<T>;
export type Exact<T extends { [key: string]: unknown }> = { [K in keyof T]: T[K] };
export type MakeOptional<T, K extends keyof T> = Omit<T, K> & { [SubKey in K]?: Maybe<T[SubKey]> };
export type MakeMaybe<T, K extends keyof T> = Omit<T, K> & { [SubKey in K]: Maybe<T[SubKey]> };
export type MakeEmpty<T extends { [key: string]: unknown }, K extends keyof T> = { [_ in K]?: never };
export type Incremental<T> = T | { [P in keyof T]?: P extends ' $fragmentName' | '__typename' ? T[P] : never };
/** All built-in and custom scalars, mapped to their actual values */
export type Scalars = {
  ID: { input: string; output: string; }
  String: { input: string; output: string; }
  Boolean: { input: boolean; output: boolean; }
  Int: { input: number; output: number; }
  Float: { input: number; output: number; }
  Int64: { input: number; output: number; }
  Time: { input: string; output: string; }
};

export type Activity = {
  __typename?: 'Activity';
  action: ActivityAction;
  actor: ActivityActor;
  at: Scalars['Time']['output'];
  batchId: Scalars['ID']['output'];
  bytes: Scalars['Int64']['output'];
  files: Scalars['Int64']['output'];
  fromPath?: Maybe<Scalars['String']['output']>;
  id: Scalars['ID']['output'];
  name: Scalars['String']['output'];
  newName?: Maybe<Scalars['String']['output']>;
  node?: Maybe<Node>;
  nodeId?: Maybe<Scalars['ID']['output']>;
  nodeKind?: Maybe<NodeKind>;
  note?: Maybe<Scalars['String']['output']>;
  oldName?: Maybe<Scalars['String']['output']>;
  parentPath: Scalars['String']['output'];
  toPath?: Maybe<Scalars['String']['output']>;
  undoOf?: Maybe<Scalars['ID']['output']>;
  undoable: Scalars['Boolean']['output'];
  undoneAt?: Maybe<Scalars['Time']['output']>;
};

export type ActivityAction =
  | 'COPY'
  | 'DELETE'
  | 'EXTRACT'
  | 'MKDIR'
  | 'MOVE'
  | 'OVERWRITE'
  | 'PURGE'
  | 'RENAME'
  | 'RESTORE'
  | 'UPLOAD';

export type ActivityActor = {
  __typename?: 'ActivityActor';
  kind: ActorKind;
  label: Scalars['String']['output'];
  ref: Scalars['String']['output'];
};

export type ActivityActorSummary = {
  __typename?: 'ActivityActorSummary';
  actor: ActivityActor;
  lastAt: Scalars['Time']['output'];
  total: Scalars['Int64']['output'];
};

export type ActivityFilter = {
  actions?: InputMaybe<Array<ActivityAction>>;
  actorKind?: InputMaybe<ActorKind>;
  actorRef?: InputMaybe<Scalars['String']['input']>;
  batchId?: InputMaybe<Scalars['ID']['input']>;
  nodeId?: InputMaybe<Scalars['ID']['input']>;
  since?: InputMaybe<Scalars['Time']['input']>;
  until?: InputMaybe<Scalars['Time']['input']>;
};

export type ActivityPage = {
  __typename?: 'ActivityPage';
  items: Array<Activity>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type ActorKind =
  | 'AGENT'
  | 'ANDROID'
  | 'CLIP'
  | 'COLLECT'
  | 'FEED'
  | 'IMG'
  | 'JOB'
  | 'SYSTEM'
  | 'WEB'
  | 'WEBDAV';

export type AdminLink = {
  __typename?: 'AdminLink';
  id: Scalars['ID']['output'];
  monthBytes: Scalars['Int64']['output'];
  monthHits: Scalars['Int64']['output'];
  name: Scalars['String']['output'];
  owner: Scalars['String']['output'];
  path: Scalars['String']['output'];
  status: LinkStatus;
};

export type AdminLinkStats = {
  __typename?: 'AdminLinkStats';
  monthBytes: Scalars['Int64']['output'];
  monthHits: Scalars['Int64']['output'];
  monthlyBytesCap: Scalars['Int64']['output'];
  top: Array<AdminLink>;
};

export type AdminOverview = {
  __typename?: 'AdminOverview';
  blobBytes: Scalars['Int64']['output'];
  blobCount: Scalars['Int']['output'];
  contentIndex: ContentIndexStats;
  taskCounts: Array<TaskCount>;
  totalUsedBytes: Scalars['Int64']['output'];
  userCount: Scalars['Int']['output'];
};

export type AdminStorage = {
  __typename?: 'AdminStorage';
  activityBytes: Scalars['Int64']['output'];
  blobs: Scalars['Int64']['output'];
  derivativeBytes: Scalars['Int64']['output'];
  derivatives: Scalars['Int64']['output'];
  logicalBytes: Scalars['Int64']['output'];
  pendingGcBytes: Scalars['Int64']['output'];
  physicalBytes: Scalars['Int64']['output'];
  textIndexBytes: Scalars['Int64']['output'];
};

export type AdminUser = {
  __typename?: 'AdminUser';
  createdAt: Scalars['Time']['output'];
  disabled: Scalars['Boolean']['output'];
  id: Scalars['ID']['output'];
  isAdmin: Scalars['Boolean']['output'];
  quotaBytes: Scalars['Int64']['output'];
  usedBytes: Scalars['Int64']['output'];
  username: Scalars['String']['output'];
};

export type AppPassword = {
  __typename?: 'AppPassword';
  createdAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  lastUsedAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
};

export type ArchiveEntry = {
  __typename?: 'ArchiveEntry';
  downloadable: Scalars['Boolean']['output'];
  flag?: Maybe<Scalars['String']['output']>;
  idx: Scalars['Int']['output'];
  isDir: Scalars['Boolean']['output'];
  modifiedAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
  path: Scalars['String']['output'];
  previewable: Scalars['Boolean']['output'];
  size: Scalars['Int64']['output'];
};

export type ArchiveEntryPage = {
  __typename?: 'ArchiveEntryPage';
  items: Array<ArchiveEntry>;
  nextCursor?: Maybe<Scalars['String']['output']>;
  total: Scalars['Int']['output'];
};

export type ArchiveInfo = {
  __typename?: 'ArchiveInfo';
  encrypted: Scalars['Boolean']['output'];
  entryCount: Scalars['Int']['output'];
  error?: Maybe<Scalars['String']['output']>;
  fileCount: Scalars['Int']['output'];
  format: Scalars['String']['output'];
  solid: Scalars['Boolean']['output'];
  status: ArchiveStatus;
  totalSize: Scalars['Int64']['output'];
};

export type ArchiveStatus =
  | 'DONE'
  | 'FAILED'
  | 'PENDING'
  | 'TRUNCATED'
  | 'UNSUPPORTED';

export type AuthPayload = {
  __typename?: 'AuthPayload';
  accessToken: Scalars['String']['output'];
  user: User;
};

export type ChatMessage = {
  __typename?: 'ChatMessage';
  body: Scalars['String']['output'];
  createdAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  node?: Maybe<Node>;
  source: Scalars['String']['output'];
};

export type Clip = {
  __typename?: 'Clip';
  assets?: Maybe<Node>;
  byline: Scalars['String']['output'];
  createdAt: Scalars['Time']['output'];
  excerpt: Scalars['String']['output'];
  id: Scalars['ID']['output'];
  images: Scalars['Int']['output'];
  node?: Maybe<Node>;
  publishedAt?: Maybe<Scalars['Time']['output']>;
  readAt?: Maybe<Scalars['Time']['output']>;
  site: Scalars['String']['output'];
  title: Scalars['String']['output'];
  url: Scalars['String']['output'];
  words: Scalars['Int']['output'];
};

export type ClipPage = {
  __typename?: 'ClipPage';
  items: Array<Clip>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type ClipPrefs = {
  __typename?: 'ClipPrefs';
  byMonth: Scalars['Boolean']['output'];
  folder: Scalars['String']['output'];
};

export type ClipPrefsInput = {
  byMonth: Scalars['Boolean']['input'];
  folder: Scalars['String']['input'];
};

export type ClipResult = {
  __typename?: 'ClipResult';
  existing?: Maybe<Clip>;
  job?: Maybe<Job>;
};

export type ClipSite = {
  __typename?: 'ClipSite';
  site: Scalars['String']['output'];
  total: Scalars['Int64']['output'];
};

export type CollectAuth = {
  __typename?: 'CollectAuth';
  accessToken: Scalars['String']['output'];
};

export type CollectUpload = {
  __typename?: 'CollectUpload';
  createdAt: Scalars['Time']['output'];
  name: Scalars['String']['output'];
  size: Scalars['Int64']['output'];
};

export type ContentHit = {
  __typename?: 'ContentHit';
  highlights: Array<TextRange>;
  node: Node;
  page?: Maybe<Scalars['Int']['output']>;
  snippet: Scalars['String']['output'];
};

export type ContentHitPage = {
  __typename?: 'ContentHitPage';
  items: Array<ContentHit>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type ContentIndexStats = {
  __typename?: 'ContentIndexStats';
  done: Scalars['Int']['output'];
  empty: Scalars['Int']['output'];
  enabled: Scalars['Boolean']['output'];
  failed: Scalars['Int']['output'];
  pending: Scalars['Int']['output'];
  textBytes: Scalars['Int64']['output'];
  truncated: Scalars['Int']['output'];
  unsupported: Scalars['Int']['output'];
};

export type DirectLink = {
  __typename?: 'DirectLink';
  allowNoReferer?: Maybe<Scalars['Boolean']['output']>;
  autoPause: Scalars['Boolean']['output'];
  bytesTotal: Scalars['Int64']['output'];
  createdAt: Scalars['Time']['output'];
  dailyBytesCap?: Maybe<Scalars['Int64']['output']>;
  expiresAt?: Maybe<Scalars['Time']['output']>;
  hasGps: Scalars['Boolean']['output'];
  hitsTotal: Scalars['Int64']['output'];
  id: Scalars['ID']['output'];
  last30Days: LinkUsage;
  lastHitAt?: Maybe<Scalars['Time']['output']>;
  mime?: Maybe<Scalars['String']['output']>;
  name: Scalars['String']['output'];
  nodeId: Scalars['ID']['output'];
  path: Scalars['String']['output'];
  pauseReason?: Maybe<Scalars['String']['output']>;
  refererHosts?: Maybe<Array<Scalars['String']['output']>>;
  size?: Maybe<Scalars['Int64']['output']>;
  source: Scalars['String']['output'];
  status: LinkStatus;
  today: LinkUsage;
  token: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type DirectLinkInput = {
  allowNoReferer?: InputMaybe<Scalars['Boolean']['input']>;
  autoPause: Scalars['Boolean']['input'];
  dailyBytesCap?: InputMaybe<Scalars['Int64']['input']>;
  expiresAt?: InputMaybe<Scalars['Time']['input']>;
  refererHosts?: InputMaybe<Array<Scalars['String']['input']>>;
};

export type DirectLinkPage = {
  __typename?: 'DirectLinkPage';
  items: Array<DirectLink>;
  nextCursor?: Maybe<Scalars['String']['output']>;
  total: Scalars['Int']['output'];
};

export type DuplicateGroup = {
  __typename?: 'DuplicateGroup';
  copies: Scalars['Int']['output'];
  files: Array<SpaceFile>;
  sha256: Scalars['String']['output'];
  size: Scalars['Int64']['output'];
};

export type DuplicatePage = {
  __typename?: 'DuplicatePage';
  items: Array<DuplicateGroup>;
  nextOffset?: Maybe<Scalars['Int']['output']>;
};

export type Feed = {
  __typename?: 'Feed';
  assetPattern: Scalars['String']['output'];
  createdAt: Scalars['Time']['output'];
  enabled: Scalars['Boolean']['output'];
  excludeWords: Scalars['String']['output'];
  failCount: Scalars['Int']['output'];
  folder?: Maybe<Node>;
  folderPath?: Maybe<Scalars['String']['output']>;
  id: Scalars['ID']['output'];
  includeWords: Scalars['String']['output'];
  intervalMinutes: Scalars['Int']['output'];
  itemCount: Scalars['Int']['output'];
  keepLast: Scalars['Int']['output'];
  kind: FeedKind;
  lastCheckedAt?: Maybe<Scalars['Time']['output']>;
  lastError?: Maybe<Scalars['String']['output']>;
  lastOkAt?: Maybe<Scalars['Time']['output']>;
  latestAt?: Maybe<Scalars['Time']['output']>;
  mode: FeedMode;
  newCount: Scalars['Int']['output'];
  nextCheckAt: Scalars['Time']['output'];
  prerelease: Scalars['Boolean']['output'];
  siteUrl: Scalars['String']['output'];
  title: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type FeedInput = {
  assetPattern?: InputMaybe<Scalars['String']['input']>;
  enabled?: InputMaybe<Scalars['Boolean']['input']>;
  excludeWords?: InputMaybe<Scalars['String']['input']>;
  folderId?: InputMaybe<Scalars['ID']['input']>;
  includeWords?: InputMaybe<Scalars['String']['input']>;
  initial?: InputMaybe<Scalars['Int']['input']>;
  intervalMinutes?: InputMaybe<Scalars['Int']['input']>;
  keepLast?: InputMaybe<Scalars['Int']['input']>;
  mode?: InputMaybe<FeedMode>;
  prerelease?: InputMaybe<Scalars['Boolean']['input']>;
  title?: InputMaybe<Scalars['String']['input']>;
  url?: InputMaybe<Scalars['String']['input']>;
};

export type FeedItem = {
  __typename?: 'FeedItem';
  createdAt: Scalars['Time']['output'];
  enclosureSize?: Maybe<Scalars['Int64']['output']>;
  enclosureType: Scalars['String']['output'];
  enclosureUrl: Scalars['String']['output'];
  error?: Maybe<Scalars['String']['output']>;
  id: Scalars['ID']['output'];
  jobId?: Maybe<Scalars['ID']['output']>;
  node?: Maybe<Node>;
  publishedAt?: Maybe<Scalars['Time']['output']>;
  state: FeedItemState;
  title: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type FeedItemPage = {
  __typename?: 'FeedItemPage';
  items: Array<FeedItem>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type FeedItemState =
  | 'DONE'
  | 'FAILED'
  | 'NEW'
  | 'QUEUED'
  | 'SKIPPED';

export type FeedKind =
  | 'GITHUB'
  | 'RSS';

export type FeedMode =
  | 'CLIP'
  | 'DOWNLOAD'
  | 'NONE';

export type FeedPreview = {
  __typename?: 'FeedPreview';
  items: Array<FeedPreviewItem>;
  kind: FeedKind;
  podcast: Scalars['Boolean']['output'];
  siteUrl: Scalars['String']['output'];
  suggestedMode: FeedMode;
  title: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type FeedPreviewItem = {
  __typename?: 'FeedPreviewItem';
  enclosureSize?: Maybe<Scalars['Int64']['output']>;
  enclosureUrl: Scalars['String']['output'];
  publishedAt?: Maybe<Scalars['Time']['output']>;
  title: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type FileCategory =
  | 'ARCHIVE'
  | 'AUDIO'
  | 'DOCUMENT'
  | 'IMAGE'
  | 'OTHER'
  | 'TEXT'
  | 'VIDEO';

export type FileRequest = {
  __typename?: 'FileRequest';
  createdAt: Scalars['Time']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  fileCount: Scalars['Int']['output'];
  folder: Node;
  groupByUploader: Scalars['Boolean']['output'];
  hasPassword: Scalars['Boolean']['output'];
  id: Scalars['ID']['output'];
  lastUploadAt?: Maybe<Scalars['Time']['output']>;
  maxFileSize?: Maybe<Scalars['Int64']['output']>;
  maxFiles?: Maybe<Scalars['Int']['output']>;
  maxTotalBytes?: Maybe<Scalars['Int64']['output']>;
  newCount: Scalars['Int']['output'];
  note: Scalars['String']['output'];
  requireName: Scalars['Boolean']['output'];
  title: Scalars['String']['output'];
  token: Scalars['String']['output'];
  totalBytes: Scalars['Int64']['output'];
  uploaderCount: Scalars['Int']['output'];
};

export type FileRequestInfo = {
  __typename?: 'FileRequestInfo';
  closed: Scalars['Boolean']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  maxFileSize?: Maybe<Scalars['Int64']['output']>;
  needName: Scalars['Boolean']['output'];
  needPassword: Scalars['Boolean']['output'];
  note: Scalars['String']['output'];
  title: Scalars['String']['output'];
};

export type FileRequestInput = {
  expiresAt?: InputMaybe<Scalars['Time']['input']>;
  groupByUploader?: InputMaybe<Scalars['Boolean']['input']>;
  maxFileSize?: InputMaybe<Scalars['Int64']['input']>;
  maxFiles?: InputMaybe<Scalars['Int']['input']>;
  maxTotalBytes?: InputMaybe<Scalars['Int64']['input']>;
  note?: InputMaybe<Scalars['String']['input']>;
  requireName?: InputMaybe<Scalars['Boolean']['input']>;
  title: Scalars['String']['input'];
};

export type FileRequestUpload = {
  __typename?: 'FileRequestUpload';
  createdAt: Scalars['Time']['output'];
  fileName: Scalars['String']['output'];
  id: Scalars['ID']['output'];
  ip?: Maybe<Scalars['String']['output']>;
  node?: Maybe<Node>;
  size: Scalars['Int64']['output'];
  uploaderName: Scalars['String']['output'];
  userAgent?: Maybe<Scalars['String']['output']>;
};

export type FileRequestUploadPage = {
  __typename?: 'FileRequestUploadPage';
  items: Array<FileRequestUpload>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type FileVersion = {
  __typename?: 'FileVersion';
  downloadUrl: Scalars['String']['output'];
  id: Scalars['ID']['output'];
  mime: Scalars['String']['output'];
  replacedAt: Scalars['Time']['output'];
  savedAt: Scalars['Time']['output'];
  sha256: Scalars['String']['output'];
  size: Scalars['Int64']['output'];
};

export type FileVersionList = {
  __typename?: 'FileVersionList';
  items: Array<FileVersion>;
  keep: Scalars['Int']['output'];
  keepDays: Scalars['Int']['output'];
};

export type IdleLink = {
  __typename?: 'IdleLink';
  createdAt: Scalars['Time']['output'];
  expired: Scalars['Boolean']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  hits: Scalars['Int64']['output'];
  id: Scalars['ID']['output'];
  lastHitAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
  nodeId: Scalars['ID']['output'];
};

export type IdleRequest = {
  __typename?: 'IdleRequest';
  createdAt: Scalars['Time']['output'];
  expiresAt: Scalars['Time']['output'];
  folderId: Scalars['ID']['output'];
  id: Scalars['ID']['output'];
  title: Scalars['String']['output'];
};

export type IdleShare = {
  __typename?: 'IdleShare';
  createdAt: Scalars['Time']['output'];
  expired: Scalars['Boolean']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  id: Scalars['ID']['output'];
  kind: NodeKind;
  lastVisitAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
  nodeId: Scalars['ID']['output'];
  token: Scalars['String']['output'];
};

export type Job = {
  __typename?: 'Job';
  attempts: Scalars['Int']['output'];
  bytesDone: Scalars['Int64']['output'];
  bytesTotal?: Maybe<Scalars['Int64']['output']>;
  createdAt: Scalars['Time']['output'];
  errorCode?: Maybe<Scalars['String']['output']>;
  errorMessage?: Maybe<Scalars['String']['output']>;
  filesDone: Scalars['Int']['output'];
  filesSkipped: Scalars['Int']['output'];
  filesTotal?: Maybe<Scalars['Int']['output']>;
  finishedAt?: Maybe<Scalars['Time']['output']>;
  id: Scalars['ID']['output'];
  kind: JobKind;
  parentId?: Maybe<Scalars['ID']['output']>;
  result?: Maybe<Node>;
  source: Scalars['String']['output'];
  startedAt?: Maybe<Scalars['Time']['output']>;
  status: JobStatus;
  title: Scalars['String']['output'];
  url?: Maybe<Scalars['String']['output']>;
};

export type JobKind =
  | 'CLIP_URL'
  | 'EXTRACT_ARCHIVE'
  | 'FETCH_URL';

export type JobPage = {
  __typename?: 'JobPage';
  items: Array<Job>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type JobStatus =
  | 'CANCELED'
  | 'DONE'
  | 'FAILED'
  | 'QUEUED'
  | 'RUNNING';

export type LinkHit = {
  __typename?: 'LinkHit';
  at: Scalars['Time']['output'];
  bytes: Scalars['Int64']['output'];
  ip: Scalars['String']['output'];
  referer: Scalars['String']['output'];
  status: Scalars['Int']['output'];
  userAgent: Scalars['String']['output'];
};

export type LinkHitPage = {
  __typename?: 'LinkHitPage';
  items: Array<LinkHit>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type LinkHourStat = {
  __typename?: 'LinkHourStat';
  blocked: Scalars['Int64']['output'];
  bytes: Scalars['Int64']['output'];
  hits: Scalars['Int64']['output'];
  hour: Scalars['Time']['output'];
  ips: Scalars['Int']['output'];
};

export type LinkPrefs = {
  __typename?: 'LinkPrefs';
  allowNoReferer: Scalars['Boolean']['output'];
  dailyBytesCapDefault: Scalars['Int64']['output'];
  imageFolder: Scalars['String']['output'];
  monthlyBytesCap: Scalars['Int64']['output'];
  monthlyBytesUsed: Scalars['Int64']['output'];
  refererHosts: Array<Scalars['String']['output']>;
  stripGps: Scalars['Boolean']['output'];
};

export type LinkPrefsInput = {
  allowNoReferer: Scalars['Boolean']['input'];
  imageFolder: Scalars['String']['input'];
  refererHosts: Array<Scalars['String']['input']>;
  stripGps: Scalars['Boolean']['input'];
};

export type LinkReferer = {
  __typename?: 'LinkReferer';
  bytes: Scalars['Int64']['output'];
  hits: Scalars['Int64']['output'];
  host: Scalars['String']['output'];
};

export type LinkStatus =
  | 'ACTIVE'
  | 'EXPIRED'
  | 'PAUSED'
  | 'UNAVAILABLE';

export type LinkUsage = {
  __typename?: 'LinkUsage';
  blocked: Scalars['Int64']['output'];
  bytes: Scalars['Int64']['output'];
  hits: Scalars['Int64']['output'];
};

export type McpapiKey = {
  __typename?: 'MCPAPIKey';
  createdAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  lastUsedAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
  scopes: Array<Scalars['String']['output']>;
};

export type McpapiKeyCreated = {
  __typename?: 'MCPAPIKeyCreated';
  credential: McpapiKey;
  token: Scalars['String']['output'];
};

export type McpAccessRoot = {
  __typename?: 'MCPAccessRoot';
  credentialId: Scalars['ID']['output'];
  credentialType: Scalars['String']['output'];
  rootId: Scalars['ID']['output'];
  rootName: Scalars['String']['output'];
};

export type McpAuditEvent = {
  __typename?: 'MCPAuditEvent';
  createdAt: Scalars['Time']['output'];
  credentialId: Scalars['ID']['output'];
  credentialType: Scalars['String']['output'];
  endpoint: Scalars['String']['output'];
  errorCode?: Maybe<Scalars['String']['output']>;
  id: Scalars['Int64']['output'];
  status: Scalars['String']['output'];
  tool: Scalars['String']['output'];
};

export type Mutation = {
  __typename?: 'Mutation';
  abortUpload: Scalars['Boolean']['output'];
  adminCreateUser: AdminUser;
  adminPauseLink: Scalars['Boolean']['output'];
  adminResetPassword: Scalars['String']['output'];
  adminRetryFailedTasks: Scalars['Int']['output'];
  adminRevokeLink: Scalars['Boolean']['output'];
  adminSetDisabled: AdminUser;
  adminSetQuota: AdminUser;
  cancelJob: Job;
  changePassword: AuthPayload;
  clearFinishedJobs: Scalars['Int']['output'];
  clearNotifications: Scalars['Int']['output'];
  clearProgress: Scalars['Boolean']['output'];
  clearRecent: Scalars['Boolean']['output'];
  clipUrl: ClipResult;
  collectAbortUpload: Scalars['Boolean']['output'];
  collectCompleteUpload: CollectUpload;
  collectInitUpload: UploadSession;
  completeUpload: Node;
  copyNodes: Array<Node>;
  createAppPassword: Scalars['String']['output'];
  createFeed: Feed;
  createFileRequest: FileRequest;
  createFolder: Node;
  createLinks: Array<DirectLink>;
  createMCPAPIKey: McpapiKeyCreated;
  createNotifyChannel: NotifyChannel;
  createShare: Share;
  createTextFile: Node;
  decideOAuthAuthorization: OAuthAuthorizationResult;
  deleteChatMessage: Scalars['Boolean']['output'];
  deleteFeed: Scalars['Boolean']['output'];
  deleteFileVersions: Scalars['Boolean']['output'];
  deleteNodes: Scalars['Boolean']['output'];
  deleteNotifyChannel: Scalars['Boolean']['output'];
  ensureChatFolder: Node;
  extractArchive: Job;
  fetchFeedItem: FeedItem;
  fetchUrls: Array<Job>;
  importOpml: OpmlImportResult;
  initUpload: UploadInit;
  login: AuthPayload;
  logout: Scalars['Boolean']['output'];
  markClipRead: Clip;
  markFileRequestSeen: Scalars['Boolean']['output'];
  markNotificationsRead: Scalars['Int']['output'];
  markOpened: Scalars['Boolean']['output'];
  moveNodes: Array<Node>;
  openFileRequest: CollectAuth;
  pauseLink: DirectLink;
  purgeFileVersions: Scalars['Int64']['output'];
  purgeNodes: Scalars['Boolean']['output'];
  purgeTrash: Scalars['Boolean']['output'];
  refresh: AuthPayload;
  refreshFeed: Feed;
  register: AuthPayload;
  renameNode: Node;
  requestPreview: PreviewInfo;
  restoreFileVersion: Node;
  restoreNodes: Array<Node>;
  retryJob: Job;
  revokeAppPassword: Scalars['Boolean']['output'];
  revokeFileRequest: Scalars['Boolean']['output'];
  revokeLink: Scalars['Boolean']['output'];
  revokeMCPAPIKey: Scalars['Boolean']['output'];
  revokeOAuthGrant: Scalars['Boolean']['output'];
  revokeSession: Scalars['Boolean']['output'];
  revokeShare: Scalars['Boolean']['output'];
  saveProgress?: Maybe<PlayProgress>;
  saveTextFile: Node;
  sendChatMessage: ChatMessage;
  setClipPrefs: ClipPrefs;
  setLinkPrefs: LinkPrefs;
  setMCPAccessRoot: Scalars['Boolean']['output'];
  setStarred: Array<Node>;
  testNotifyChannel: Scalars['Boolean']['output'];
  undoActivities: UndoResult;
  updateFeed: Feed;
  updateFileRequest: FileRequest;
  updateLink: DirectLink;
  updateNotifyChannel: NotifyChannel;
  verifySharePassword: ShareAuth;
};


export type MutationAbortUploadArgs = {
  sessionId: Scalars['ID']['input'];
};


export type MutationAdminCreateUserArgs = {
  password: Scalars['String']['input'];
  quotaBytes?: InputMaybe<Scalars['Int64']['input']>;
  username: Scalars['String']['input'];
};


export type MutationAdminPauseLinkArgs = {
  id: Scalars['ID']['input'];
  paused: Scalars['Boolean']['input'];
};


export type MutationAdminResetPasswordArgs = {
  userId: Scalars['ID']['input'];
};


export type MutationAdminRevokeLinkArgs = {
  id: Scalars['ID']['input'];
};


export type MutationAdminSetDisabledArgs = {
  disabled: Scalars['Boolean']['input'];
  userId: Scalars['ID']['input'];
};


export type MutationAdminSetQuotaArgs = {
  quotaBytes: Scalars['Int64']['input'];
  userId: Scalars['ID']['input'];
};


export type MutationCancelJobArgs = {
  id: Scalars['ID']['input'];
};


export type MutationChangePasswordArgs = {
  newPassword: Scalars['String']['input'];
  oldPassword: Scalars['String']['input'];
};


export type MutationClearProgressArgs = {
  nodeId: Scalars['ID']['input'];
};


export type MutationClearRecentArgs = {
  ids?: InputMaybe<Array<Scalars['ID']['input']>>;
};


export type MutationClipUrlArgs = {
  force?: InputMaybe<Scalars['Boolean']['input']>;
  html?: InputMaybe<Scalars['String']['input']>;
  url: Scalars['String']['input'];
};


export type MutationCollectAbortUploadArgs = {
  sessionId: Scalars['ID']['input'];
};


export type MutationCollectCompleteUploadArgs = {
  etags: Array<PartEtag>;
  sessionId: Scalars['ID']['input'];
};


export type MutationCollectInitUploadArgs = {
  name: Scalars['String']['input'];
  size: Scalars['Int64']['input'];
};


export type MutationCompleteUploadArgs = {
  etags: Array<PartEtag>;
  sessionId: Scalars['ID']['input'];
};


export type MutationCopyNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
  targetParentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationCreateAppPasswordArgs = {
  name: Scalars['String']['input'];
};


export type MutationCreateFeedArgs = {
  input: FeedInput;
};


export type MutationCreateFileRequestArgs = {
  folderId: Scalars['ID']['input'];
  input: FileRequestInput;
  password?: InputMaybe<Scalars['String']['input']>;
};


export type MutationCreateFolderArgs = {
  name: Scalars['String']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationCreateLinksArgs = {
  nodeIds: Array<Scalars['ID']['input']>;
};


export type MutationCreateMcpapiKeyArgs = {
  name: Scalars['String']['input'];
  scopes: Array<Scalars['String']['input']>;
};


export type MutationCreateNotifyChannelArgs = {
  input: NotifyChannelInput;
};


export type MutationCreateShareArgs = {
  expiresAt?: InputMaybe<Scalars['Time']['input']>;
  nodeId: Scalars['ID']['input'];
  password?: InputMaybe<Scalars['String']['input']>;
};


export type MutationCreateTextFileArgs = {
  content?: InputMaybe<Scalars['String']['input']>;
  name: Scalars['String']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationDecideOAuthAuthorizationArgs = {
  approved: Scalars['Boolean']['input'];
  input: OAuthAuthorizationInput;
};


export type MutationDeleteChatMessageArgs = {
  id: Scalars['ID']['input'];
};


export type MutationDeleteFeedArgs = {
  id: Scalars['ID']['input'];
};


export type MutationDeleteFileVersionsArgs = {
  ids: Array<Scalars['ID']['input']>;
};


export type MutationDeleteNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
};


export type MutationDeleteNotifyChannelArgs = {
  id: Scalars['ID']['input'];
};


export type MutationExtractArchiveArgs = {
  base?: InputMaybe<Scalars['String']['input']>;
  nodeId: Scalars['ID']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
  paths?: InputMaybe<Array<Scalars['String']['input']>>;
};


export type MutationFetchFeedItemArgs = {
  itemId: Scalars['ID']['input'];
  mode: FeedMode;
};


export type MutationFetchUrlsArgs = {
  parentId?: InputMaybe<Scalars['ID']['input']>;
  urls: Array<Scalars['String']['input']>;
};


export type MutationImportOpmlArgs = {
  opml: Scalars['String']['input'];
};


export type MutationInitUploadArgs = {
  name: Scalars['String']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
  replace?: InputMaybe<Scalars['Boolean']['input']>;
  sha256: Scalars['String']['input'];
  size: Scalars['Int64']['input'];
};


export type MutationLoginArgs = {
  password: Scalars['String']['input'];
  username: Scalars['String']['input'];
};


export type MutationMarkClipReadArgs = {
  id: Scalars['ID']['input'];
  read?: InputMaybe<Scalars['Boolean']['input']>;
};


export type MutationMarkFileRequestSeenArgs = {
  id: Scalars['ID']['input'];
};


export type MutationMarkNotificationsReadArgs = {
  ids?: InputMaybe<Array<Scalars['ID']['input']>>;
};


export type MutationMarkOpenedArgs = {
  nodeId: Scalars['ID']['input'];
};


export type MutationMoveNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
  targetParentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationOpenFileRequestArgs = {
  password?: InputMaybe<Scalars['String']['input']>;
  token: Scalars['String']['input'];
  uploaderName?: InputMaybe<Scalars['String']['input']>;
};


export type MutationPauseLinkArgs = {
  id: Scalars['ID']['input'];
  paused: Scalars['Boolean']['input'];
};


export type MutationPurgeFileVersionsArgs = {
  nodeId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationPurgeNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
};


export type MutationRefreshFeedArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRegisterArgs = {
  password: Scalars['String']['input'];
  username: Scalars['String']['input'];
};


export type MutationRenameNodeArgs = {
  id: Scalars['ID']['input'];
  name: Scalars['String']['input'];
};


export type MutationRequestPreviewArgs = {
  nodeId: Scalars['ID']['input'];
};


export type MutationRestoreFileVersionArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRestoreNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
};


export type MutationRetryJobArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeAppPasswordArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeFileRequestArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeLinkArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeMcpapiKeyArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeOAuthGrantArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeSessionArgs = {
  familyId: Scalars['ID']['input'];
};


export type MutationRevokeShareArgs = {
  id: Scalars['ID']['input'];
};


export type MutationSaveProgressArgs = {
  duration: Scalars['Float']['input'];
  nodeId: Scalars['ID']['input'];
  position: Scalars['Float']['input'];
};


export type MutationSaveTextFileArgs = {
  baseSha256?: InputMaybe<Scalars['String']['input']>;
  content: Scalars['String']['input'];
  nodeId: Scalars['ID']['input'];
};


export type MutationSendChatMessageArgs = {
  body?: InputMaybe<Scalars['String']['input']>;
  nodeId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationSetClipPrefsArgs = {
  input: ClipPrefsInput;
};


export type MutationSetLinkPrefsArgs = {
  input: LinkPrefsInput;
};


export type MutationSetMcpAccessRootArgs = {
  credentialId: Scalars['ID']['input'];
  credentialType: Scalars['String']['input'];
  rootPath?: InputMaybe<Scalars['String']['input']>;
};


export type MutationSetStarredArgs = {
  ids: Array<Scalars['ID']['input']>;
  starred: Scalars['Boolean']['input'];
};


export type MutationTestNotifyChannelArgs = {
  id: Scalars['ID']['input'];
};


export type MutationUndoActivitiesArgs = {
  selection: UndoSelection;
};


export type MutationUpdateFeedArgs = {
  id: Scalars['ID']['input'];
  input: FeedInput;
};


export type MutationUpdateFileRequestArgs = {
  id: Scalars['ID']['input'];
  input: FileRequestInput;
};


export type MutationUpdateLinkArgs = {
  id: Scalars['ID']['input'];
  input: DirectLinkInput;
};


export type MutationUpdateNotifyChannelArgs = {
  id: Scalars['ID']['input'];
  input: NotifyChannelInput;
};


export type MutationVerifySharePasswordArgs = {
  password: Scalars['String']['input'];
  token: Scalars['String']['input'];
};

export type Node = {
  __typename?: 'Node';
  createdAt: Scalars['Time']['output'];
  deletedAt?: Maybe<Scalars['Time']['output']>;
  downloadUrl?: Maybe<Scalars['String']['output']>;
  id: Scalars['ID']['output'];
  kind: NodeKind;
  mime?: Maybe<Scalars['String']['output']>;
  name: Scalars['String']['output'];
  openedAt?: Maybe<Scalars['Time']['output']>;
  parentId?: Maybe<Scalars['ID']['output']>;
  path?: Maybe<Scalars['String']['output']>;
  playProgress?: Maybe<PlayProgress>;
  preview: PreviewInfo;
  purgeAt?: Maybe<Scalars['Time']['output']>;
  sha256?: Maybe<Scalars['String']['output']>;
  size?: Maybe<Scalars['Int64']['output']>;
  starred: Scalars['Boolean']['output'];
  statsStale?: Maybe<Scalars['Boolean']['output']>;
  subtreeBytes?: Maybe<Scalars['Int64']['output']>;
  subtreeCount?: Maybe<Scalars['Int64']['output']>;
  updatedAt: Scalars['Time']['output'];
};

export type NodeKind =
  | 'FILE'
  | 'FOLDER';

export type NodeOrder =
  | 'NAME'
  | 'SIZE'
  | 'UPDATED_AT';

export type NodePage = {
  __typename?: 'NodePage';
  items: Array<Node>;
  nextCursor?: Maybe<Scalars['String']['output']>;
  total: Scalars['Int']['output'];
};

export type Notification = {
  __typename?: 'Notification';
  body: Scalars['String']['output'];
  count: Scalars['Int']['output'];
  createdAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  kind: Scalars['String']['output'];
  level: NotificationLevel;
  read: Scalars['Boolean']['output'];
  target?: Maybe<NotificationTarget>;
  title: Scalars['String']['output'];
  updatedAt: Scalars['Time']['output'];
};

export type NotificationLevel =
  | 'ALERT'
  | 'INFO'
  | 'WARN';

export type NotificationPage = {
  __typename?: 'NotificationPage';
  items: Array<Notification>;
  nextCursor?: Maybe<Scalars['String']['output']>;
};

export type NotificationTarget = {
  __typename?: 'NotificationTarget';
  id?: Maybe<Scalars['ID']['output']>;
  kind: NotificationTargetKind;
  parentId?: Maybe<Scalars['ID']['output']>;
};

export type NotificationTargetKind =
  | 'ACTIVITY'
  | 'ADMIN'
  | 'CREDENTIALS'
  | 'FEED'
  | 'FILE_REQUEST'
  | 'JOBS'
  | 'LINK'
  | 'NODE'
  | 'SESSIONS';

export type NotifyChannel = {
  __typename?: 'NotifyChannel';
  createdAt: Scalars['Time']['output'];
  enabled: Scalars['Boolean']['output'];
  events: Array<Scalars['String']['output']>;
  hasSecret: Scalars['Boolean']['output'];
  id: Scalars['ID']['output'];
  kind: NotifyChannelKind;
  lastError?: Maybe<Scalars['String']['output']>;
  lastErrorAt?: Maybe<Scalars['Time']['output']>;
  lastOkAt?: Maybe<Scalars['Time']['output']>;
  name: Scalars['String']['output'];
  secretHint: Scalars['String']['output'];
  topic: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type NotifyChannelInput = {
  enabled: Scalars['Boolean']['input'];
  events: Array<Scalars['String']['input']>;
  kind: NotifyChannelKind;
  name: Scalars['String']['input'];
  secret?: InputMaybe<Scalars['String']['input']>;
  topic?: InputMaybe<Scalars['String']['input']>;
  url?: InputMaybe<Scalars['String']['input']>;
};

export type NotifyChannelKind =
  | 'BARK'
  | 'NTFY'
  | 'TELEGRAM'
  | 'WEBHOOK';

export type NotifyEventKind = {
  __typename?: 'NotifyEventKind';
  adminOnly: Scalars['Boolean']['output'];
  defaultPush: Scalars['Boolean']['output'];
  kind: Scalars['String']['output'];
  label: Scalars['String']['output'];
};

export type OAuthAuthorizationInput = {
  clientId: Scalars['String']['input'];
  codeChallenge: Scalars['String']['input'];
  codeChallengeMethod: Scalars['String']['input'];
  redirectUri: Scalars['String']['input'];
  responseType: Scalars['String']['input'];
  scope?: InputMaybe<Scalars['String']['input']>;
  state?: InputMaybe<Scalars['String']['input']>;
};

export type OAuthAuthorizationRequest = {
  __typename?: 'OAuthAuthorizationRequest';
  clientName: Scalars['String']['output'];
  scopes: Array<Scalars['String']['output']>;
};

export type OAuthAuthorizationResult = {
  __typename?: 'OAuthAuthorizationResult';
  redirectUrl: Scalars['String']['output'];
};

export type OAuthGrant = {
  __typename?: 'OAuthGrant';
  clientId: Scalars['String']['output'];
  clientName: Scalars['String']['output'];
  createdAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  scopes: Array<Scalars['String']['output']>;
  updatedAt: Scalars['Time']['output'];
};

export type OpmlFailure = {
  __typename?: 'OpmlFailure';
  reason: Scalars['String']['output'];
  url: Scalars['String']['output'];
};

export type OpmlImportResult = {
  __typename?: 'OpmlImportResult';
  added: Scalars['Int']['output'];
  existed: Scalars['Int']['output'];
  failed: Array<OpmlFailure>;
};

export type PartEtag = {
  etag: Scalars['String']['input'];
  partNumber: Scalars['Int']['input'];
};

export type PartUrl = {
  __typename?: 'PartUrl';
  partNumber: Scalars['Int']['output'];
  url: Scalars['String']['output'];
};

export type PlayProgress = {
  __typename?: 'PlayProgress';
  duration: Scalars['Float']['output'];
  finished: Scalars['Boolean']['output'];
  position: Scalars['Float']['output'];
  updatedAt: Scalars['Time']['output'];
};

export type PreviewInfo = {
  __typename?: 'PreviewInfo';
  contentUrl?: Maybe<Scalars['String']['output']>;
  durationSec?: Maybe<Scalars['Int']['output']>;
  kind: PreviewKind;
  largeUrl?: Maybe<Scalars['String']['output']>;
  markdown: Scalars['Boolean']['output'];
  status?: Maybe<TaskStatus>;
  subtitles: Array<SubtitleTrack>;
  thumbUrl?: Maybe<Scalars['String']['output']>;
};

export type PreviewKind =
  | 'ARCHIVE'
  | 'AUDIO'
  | 'IMAGE'
  | 'NATIVE_PDF'
  | 'NONE'
  | 'OFFICE'
  | 'PDF'
  | 'TEXT'
  | 'VIDEO';

export type Query = {
  __typename?: 'Query';
  activities: ActivityPage;
  activityActors: Array<ActivityActorSummary>;
  adminLinkStats: AdminLinkStats;
  adminOverview: AdminOverview;
  adminStorage: AdminStorage;
  adminUsers: Array<AdminUser>;
  appPasswords: Array<AppPassword>;
  archiveEntries: ArchiveEntryPage;
  archiveInfo: ArchiveInfo;
  chatFolder?: Maybe<Node>;
  chatMessages: Array<ChatMessage>;
  children: NodePage;
  clip: Clip;
  clipPrefs: ClipPrefs;
  clipSites: Array<ClipSite>;
  clips: ClipPage;
  collectMyUploads: Array<CollectUpload>;
  collectUploadSession: UploadSession;
  exportOpml: Scalars['String']['output'];
  feed: Feed;
  feedItems: FeedItemPage;
  feeds: Array<Feed>;
  fetchEnabled: Scalars['Boolean']['output'];
  fileRequestInfo: FileRequestInfo;
  fileRequestUploads: FileRequestUploadPage;
  fileVersions: FileVersionList;
  job: Job;
  link: DirectLink;
  linkHits: LinkHitPage;
  linkPrefs: LinkPrefs;
  linkReferers: Array<LinkReferer>;
  linkStats: Array<LinkHourStat>;
  linksEnabled: Scalars['Boolean']['output'];
  mcpAPIKeys: Array<McpapiKey>;
  mcpAccessRoots: Array<McpAccessRoot>;
  mcpAudit: Array<McpAuditEvent>;
  me: User;
  myFileRequests: Array<FileRequest>;
  myJobs: JobPage;
  myLinks: DirectLinkPage;
  myShares: Array<Share>;
  node: Node;
  nodeClip?: Maybe<Clip>;
  nodeLink?: Maybe<DirectLink>;
  notifications: NotificationPage;
  notifyChannels: Array<NotifyChannel>;
  notifyEventKinds: Array<NotifyEventKind>;
  oauthAuthorizationRequest: OAuthAuthorizationRequest;
  oauthGrants: Array<OAuthGrant>;
  playlist: Array<Node>;
  previewFeed: FeedPreview;
  recentFiles: Array<Node>;
  recentPlays: Array<RecentPlay>;
  searchContent: ContentHitPage;
  searchNodes: NodePage;
  sessions: Array<Session>;
  shareInfo: ShareInfo;
  shareRoot: Node;
  shareVisits: Array<ShareVisit>;
  spaceDuplicates: DuplicatePage;
  spaceEmptyFolders: Array<SpaceFile>;
  spaceFolders: SpaceFolderLevel;
  spaceIdle: SpaceIdle;
  spaceLargeFiles: Array<SpaceFile>;
  spaceOverview: SpaceOverview;
  spaceStaleFiles: Array<SpaceFile>;
  starredNodes: Array<Node>;
  textFile: TextFile;
  trash: NodePage;
  undoPreview: UndoResult;
  uploadSession: UploadSession;
};


export type QueryActivitiesArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  filter?: InputMaybe<ActivityFilter>;
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QueryArchiveEntriesArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  nodeId: Scalars['ID']['input'];
  path?: InputMaybe<Scalars['String']['input']>;
};


export type QueryArchiveInfoArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryChatMessagesArgs = {
  after?: InputMaybe<Scalars['ID']['input']>;
  before?: InputMaybe<Scalars['ID']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
  query?: InputMaybe<Scalars['String']['input']>;
};


export type QueryChildrenArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  desc?: InputMaybe<Scalars['Boolean']['input']>;
  order?: InputMaybe<NodeOrder>;
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type QueryClipArgs = {
  id: Scalars['ID']['input'];
};


export type QueryClipsArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  site?: InputMaybe<Scalars['String']['input']>;
  unreadOnly?: InputMaybe<Scalars['Boolean']['input']>;
};


export type QueryCollectUploadSessionArgs = {
  id: Scalars['ID']['input'];
};


export type QueryFeedArgs = {
  id: Scalars['ID']['input'];
};


export type QueryFeedItemsArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  feedId: Scalars['ID']['input'];
};


export type QueryFileRequestInfoArgs = {
  token: Scalars['String']['input'];
};


export type QueryFileRequestUploadsArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  id: Scalars['ID']['input'];
};


export type QueryFileVersionsArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryJobArgs = {
  id: Scalars['ID']['input'];
};


export type QueryLinkArgs = {
  id: Scalars['ID']['input'];
};


export type QueryLinkHitsArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  id: Scalars['ID']['input'];
};


export type QueryLinkReferersArgs = {
  days?: InputMaybe<Scalars['Int']['input']>;
  id: Scalars['ID']['input'];
};


export type QueryLinkStatsArgs = {
  hours?: InputMaybe<Scalars['Int']['input']>;
  id: Scalars['ID']['input'];
};


export type QueryMcpAuditArgs = {
  beforeId?: InputMaybe<Scalars['Int64']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QueryMyJobsArgs = {
  active?: InputMaybe<Scalars['Boolean']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
};


export type QueryMyLinksArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
};


export type QueryNodeArgs = {
  id: Scalars['ID']['input'];
};


export type QueryNodeClipArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryNodeLinkArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryNotificationsArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  unreadOnly?: InputMaybe<Scalars['Boolean']['input']>;
};


export type QueryOauthAuthorizationRequestArgs = {
  input: OAuthAuthorizationInput;
};


export type QueryPlaylistArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryPreviewFeedArgs = {
  assetPattern?: InputMaybe<Scalars['String']['input']>;
  prerelease?: InputMaybe<Scalars['Boolean']['input']>;
  url: Scalars['String']['input'];
};


export type QueryRecentFilesArgs = {
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QueryRecentPlaysArgs = {
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QuerySearchContentArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  q: Scalars['String']['input'];
};


export type QuerySearchNodesArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  q: Scalars['String']['input'];
};


export type QueryShareInfoArgs = {
  token: Scalars['String']['input'];
};


export type QueryShareVisitsArgs = {
  limit?: InputMaybe<Scalars['Int']['input']>;
  shareId: Scalars['ID']['input'];
};


export type QuerySpaceDuplicatesArgs = {
  limit?: InputMaybe<Scalars['Int']['input']>;
  offset?: InputMaybe<Scalars['Int']['input']>;
};


export type QuerySpaceEmptyFoldersArgs = {
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QuerySpaceFoldersArgs = {
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type QuerySpaceLargeFilesArgs = {
  category?: InputMaybe<FileCategory>;
  limit?: InputMaybe<Scalars['Int']['input']>;
};


export type QuerySpaceStaleFilesArgs = {
  days?: InputMaybe<Scalars['Int']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
  minSize?: InputMaybe<Scalars['Int64']['input']>;
};


export type QueryTextFileArgs = {
  nodeId: Scalars['ID']['input'];
};


export type QueryTrashArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
};


export type QueryUndoPreviewArgs = {
  selection: UndoSelection;
};


export type QueryUploadSessionArgs = {
  id: Scalars['ID']['input'];
};

export type RecentPlay = {
  __typename?: 'RecentPlay';
  node: Node;
  progress: PlayProgress;
};

export type Session = {
  __typename?: 'Session';
  active: Scalars['Boolean']['output'];
  createdAt: Scalars['Time']['output'];
  current: Scalars['Boolean']['output'];
  familyId: Scalars['ID']['output'];
  ip: Scalars['String']['output'];
  lastSeenAt: Scalars['Time']['output'];
  revokedAt?: Maybe<Scalars['Time']['output']>;
  userAgent: Scalars['String']['output'];
};

export type Share = {
  __typename?: 'Share';
  createdAt: Scalars['Time']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  hasPassword: Scalars['Boolean']['output'];
  id: Scalars['ID']['output'];
  node: Node;
  token: Scalars['String']['output'];
  visitStats: ShareVisitStats;
};

export type ShareAuth = {
  __typename?: 'ShareAuth';
  accessToken: Scalars['String']['output'];
};

export type ShareInfo = {
  __typename?: 'ShareInfo';
  expired: Scalars['Boolean']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  fileCount?: Maybe<Scalars['Int64']['output']>;
  kind: NodeKind;
  name: Scalars['String']['output'];
  needPassword: Scalars['Boolean']['output'];
  size?: Maybe<Scalars['Int64']['output']>;
  token: Scalars['String']['output'];
};

export type ShareVisit = {
  __typename?: 'ShareVisit';
  createdAt: Scalars['Time']['output'];
  ip?: Maybe<Scalars['String']['output']>;
  kind: Scalars['String']['output'];
  userAgent?: Maybe<Scalars['String']['output']>;
};

export type ShareVisitStats = {
  __typename?: 'ShareVisitStats';
  downloadCount: Scalars['Int64']['output'];
  lastVisitAt?: Maybe<Scalars['Time']['output']>;
  lastVisitKind?: Maybe<Scalars['String']['output']>;
  verifyCount: Scalars['Int64']['output'];
};

export type SpaceCategory = {
  __typename?: 'SpaceCategory';
  bytes: Scalars['Int64']['output'];
  category: FileCategory;
  files: Scalars['Int64']['output'];
};

export type SpaceFile = {
  __typename?: 'SpaceFile';
  category?: Maybe<FileCategory>;
  node: Node;
  path: Scalars['String']['output'];
};

export type SpaceFolderLevel = {
  __typename?: 'SpaceFolderLevel';
  folders: Array<Node>;
  looseBytes: Scalars['Int64']['output'];
  looseFiles: Scalars['Int64']['output'];
  path: Scalars['String']['output'];
};

export type SpaceIdle = {
  __typename?: 'SpaceIdle';
  links: Array<IdleLink>;
  requests: Array<IdleRequest>;
  shares: Array<IdleShare>;
};

export type SpaceOverview = {
  __typename?: 'SpaceOverview';
  byType: Array<SpaceCategory>;
  duplicateBytes: Scalars['Int64']['output'];
  duplicateGroups: Scalars['Int64']['output'];
  quotaBytes: Scalars['Int64']['output'];
  trashBytes: Scalars['Int64']['output'];
  trashFiles: Scalars['Int64']['output'];
  usedBytes: Scalars['Int64']['output'];
  versionBytes: Scalars['Int64']['output'];
  versionFiles: Scalars['Int64']['output'];
};

export type SubtitleTrack = {
  __typename?: 'SubtitleTrack';
  default: Scalars['Boolean']['output'];
  label: Scalars['String']['output'];
  lang: Scalars['String']['output'];
  name: Scalars['String']['output'];
  nodeId: Scalars['ID']['output'];
};

export type TaskCount = {
  __typename?: 'TaskCount';
  count: Scalars['Int']['output'];
  status: Scalars['String']['output'];
};

export type TaskStatus =
  | 'DONE'
  | 'FAILED'
  | 'PENDING'
  | 'RUNNING'
  | 'UNAVAILABLE';

export type TextFile = {
  __typename?: 'TextFile';
  content: Scalars['String']['output'];
  sha256: Scalars['String']['output'];
  size: Scalars['Int64']['output'];
};

export type TextRange = {
  __typename?: 'TextRange';
  length: Scalars['Int']['output'];
  start: Scalars['Int']['output'];
};

export type UndoItem = {
  __typename?: 'UndoItem';
  activity: Activity;
  ok: Scalars['Boolean']['output'];
  reason?: Maybe<Scalars['String']['output']>;
};

export type UndoResult = {
  __typename?: 'UndoResult';
  done: Scalars['Int']['output'];
  failed: Scalars['Int']['output'];
  items: Array<UndoItem>;
};

export type UndoSelection = {
  batchId?: InputMaybe<Scalars['ID']['input']>;
  filter?: InputMaybe<ActivityFilter>;
  ids?: InputMaybe<Array<Scalars['ID']['input']>>;
};

export type UploadInit = {
  __typename?: 'UploadInit';
  instant: Scalars['Boolean']['output'];
  node?: Maybe<Node>;
  session?: Maybe<UploadSession>;
};

export type UploadSession = {
  __typename?: 'UploadSession';
  expiresAt: Scalars['Time']['output'];
  id: Scalars['ID']['output'];
  partSize: Scalars['Int']['output'];
  partUrls: Array<PartUrl>;
  status: Scalars['String']['output'];
  uploadedParts: Array<Scalars['Int']['output']>;
};

export type User = {
  __typename?: 'User';
  id: Scalars['ID']['output'];
  isAdmin: Scalars['Boolean']['output'];
  quotaBytes: Scalars['Int64']['output'];
  unreadNotifications: Scalars['Int']['output'];
  usedBytes: Scalars['Int64']['output'];
  username: Scalars['String']['output'];
};

export type ActivityFieldsFragment = { __typename?: 'Activity', id: string, at: string, batchId: string, action: ActivityAction, nodeId?: string | null, nodeKind?: NodeKind | null, name: string, parentPath: string, fromPath?: string | null, toPath?: string | null, oldName?: string | null, newName?: string | null, files: number, bytes: number, note?: string | null, undoOf?: string | null, undoneAt?: string | null, undoable: boolean, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string }, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null };

export type ActivitiesQueryVariables = Exact<{
  filter?: InputMaybe<ActivityFilter>;
  cursor?: InputMaybe<Scalars['String']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type ActivitiesQuery = { __typename?: 'Query', activities: { __typename?: 'ActivityPage', nextCursor?: string | null, items: Array<{ __typename?: 'Activity', id: string, at: string, batchId: string, action: ActivityAction, nodeId?: string | null, nodeKind?: NodeKind | null, name: string, parentPath: string, fromPath?: string | null, toPath?: string | null, oldName?: string | null, newName?: string | null, files: number, bytes: number, note?: string | null, undoOf?: string | null, undoneAt?: string | null, undoable: boolean, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string }, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null }> } };

export type ActivityActorsQueryVariables = Exact<{ [key: string]: never; }>;


export type ActivityActorsQuery = { __typename?: 'Query', activityActors: Array<{ __typename?: 'ActivityActorSummary', total: number, lastAt: string, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string } }> };

export type UndoResultFieldsFragment = { __typename?: 'UndoResult', done: number, failed: number, items: Array<{ __typename?: 'UndoItem', ok: boolean, reason?: string | null, activity: { __typename?: 'Activity', id: string, at: string, batchId: string, action: ActivityAction, nodeId?: string | null, nodeKind?: NodeKind | null, name: string, parentPath: string, fromPath?: string | null, toPath?: string | null, oldName?: string | null, newName?: string | null, files: number, bytes: number, note?: string | null, undoOf?: string | null, undoneAt?: string | null, undoable: boolean, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string }, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } }> };

export type UndoPreviewQueryVariables = Exact<{
  selection: UndoSelection;
}>;


export type UndoPreviewQuery = { __typename?: 'Query', undoPreview: { __typename?: 'UndoResult', done: number, failed: number, items: Array<{ __typename?: 'UndoItem', ok: boolean, reason?: string | null, activity: { __typename?: 'Activity', id: string, at: string, batchId: string, action: ActivityAction, nodeId?: string | null, nodeKind?: NodeKind | null, name: string, parentPath: string, fromPath?: string | null, toPath?: string | null, oldName?: string | null, newName?: string | null, files: number, bytes: number, note?: string | null, undoOf?: string | null, undoneAt?: string | null, undoable: boolean, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string }, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } }> } };

export type UndoActivitiesMutationVariables = Exact<{
  selection: UndoSelection;
}>;


export type UndoActivitiesMutation = { __typename?: 'Mutation', undoActivities: { __typename?: 'UndoResult', done: number, failed: number, items: Array<{ __typename?: 'UndoItem', ok: boolean, reason?: string | null, activity: { __typename?: 'Activity', id: string, at: string, batchId: string, action: ActivityAction, nodeId?: string | null, nodeKind?: NodeKind | null, name: string, parentPath: string, fromPath?: string | null, toPath?: string | null, oldName?: string | null, newName?: string | null, files: number, bytes: number, note?: string | null, undoOf?: string | null, undoneAt?: string | null, undoable: boolean, actor: { __typename?: 'ActivityActor', kind: ActorKind, ref: string, label: string }, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } }> } };

export type AdminUsersQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminUsersQuery = { __typename?: 'Query', adminUsers: Array<{ __typename?: 'AdminUser', id: string, username: string, isAdmin: boolean, disabled: boolean, quotaBytes: number, usedBytes: number, createdAt: string }> };

export type AdminOverviewQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminOverviewQuery = { __typename?: 'Query', adminOverview: { __typename?: 'AdminOverview', userCount: number, totalUsedBytes: number, blobCount: number, blobBytes: number, taskCounts: Array<{ __typename?: 'TaskCount', status: string, count: number }>, contentIndex: { __typename?: 'ContentIndexStats', enabled: boolean, done: number, empty: number, truncated: number, unsupported: number, failed: number, pending: number, textBytes: number } } };

export type AdminCreateUserMutationVariables = Exact<{
  username: Scalars['String']['input'];
  password: Scalars['String']['input'];
  quotaBytes?: InputMaybe<Scalars['Int64']['input']>;
}>;


export type AdminCreateUserMutation = { __typename?: 'Mutation', adminCreateUser: { __typename?: 'AdminUser', id: string, username: string, disabled: boolean, quotaBytes: number, usedBytes: number } };

export type AdminSetQuotaMutationVariables = Exact<{
  userId: Scalars['ID']['input'];
  quotaBytes: Scalars['Int64']['input'];
}>;


export type AdminSetQuotaMutation = { __typename?: 'Mutation', adminSetQuota: { __typename?: 'AdminUser', id: string, quotaBytes: number } };

export type AdminSetDisabledMutationVariables = Exact<{
  userId: Scalars['ID']['input'];
  disabled: Scalars['Boolean']['input'];
}>;


export type AdminSetDisabledMutation = { __typename?: 'Mutation', adminSetDisabled: { __typename?: 'AdminUser', id: string, disabled: boolean } };

export type AdminResetPasswordMutationVariables = Exact<{
  userId: Scalars['ID']['input'];
}>;


export type AdminResetPasswordMutation = { __typename?: 'Mutation', adminResetPassword: string };

export type AdminRetryFailedTasksMutationVariables = Exact<{ [key: string]: never; }>;


export type AdminRetryFailedTasksMutation = { __typename?: 'Mutation', adminRetryFailedTasks: number };

export type AppPasswordsQueryVariables = Exact<{ [key: string]: never; }>;


export type AppPasswordsQuery = { __typename?: 'Query', appPasswords: Array<{ __typename?: 'AppPassword', id: string, name: string, createdAt: string, lastUsedAt?: string | null }> };

export type CreateAppPasswordMutationVariables = Exact<{
  name: Scalars['String']['input'];
}>;


export type CreateAppPasswordMutation = { __typename?: 'Mutation', createAppPassword: string };

export type RevokeAppPasswordMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeAppPasswordMutation = { __typename?: 'Mutation', revokeAppPassword: boolean };

export type ArchiveInfoQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type ArchiveInfoQuery = { __typename?: 'Query', archiveInfo: { __typename?: 'ArchiveInfo', status: ArchiveStatus, format: string, entryCount: number, fileCount: number, totalSize: number, solid: boolean, encrypted: boolean, error?: string | null } };

export type ArchiveEntriesQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
  path?: InputMaybe<Scalars['String']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type ArchiveEntriesQuery = { __typename?: 'Query', archiveEntries: { __typename?: 'ArchiveEntryPage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'ArchiveEntry', idx: number, path: string, name: string, isDir: boolean, size: number, modifiedAt?: string | null, flag?: string | null, previewable: boolean, downloadable: boolean }> } };

export type ExtractArchiveMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
  base?: InputMaybe<Scalars['String']['input']>;
  paths?: InputMaybe<Array<Scalars['String']['input']> | Scalars['String']['input']>;
  parentId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type ExtractArchiveMutation = { __typename?: 'Mutation', extractArchive: { __typename?: 'Job', id: string, status: JobStatus, title: string } };

export type LoginMutationVariables = Exact<{
  username: Scalars['String']['input'];
  password: Scalars['String']['input'];
}>;


export type LoginMutation = { __typename?: 'Mutation', login: { __typename?: 'AuthPayload', accessToken: string, user: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } } };

export type RegisterMutationVariables = Exact<{
  username: Scalars['String']['input'];
  password: Scalars['String']['input'];
}>;


export type RegisterMutation = { __typename?: 'Mutation', register: { __typename?: 'AuthPayload', accessToken: string, user: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } } };

export type RefreshMutationVariables = Exact<{ [key: string]: never; }>;


export type RefreshMutation = { __typename?: 'Mutation', refresh: { __typename?: 'AuthPayload', accessToken: string, user: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } } };

export type LogoutMutationVariables = Exact<{ [key: string]: never; }>;


export type LogoutMutation = { __typename?: 'Mutation', logout: boolean };

export type MeQueryVariables = Exact<{ [key: string]: never; }>;


export type MeQuery = { __typename?: 'Query', me: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean, unreadNotifications: number } };

export type ChangePasswordMutationVariables = Exact<{
  oldPassword: Scalars['String']['input'];
  newPassword: Scalars['String']['input'];
}>;


export type ChangePasswordMutation = { __typename?: 'Mutation', changePassword: { __typename?: 'AuthPayload', accessToken: string, user: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } } };

export type ChatMessagesQueryVariables = Exact<{
  limit?: InputMaybe<Scalars['Int']['input']>;
  before?: InputMaybe<Scalars['ID']['input']>;
  after?: InputMaybe<Scalars['ID']['input']>;
  query?: InputMaybe<Scalars['String']['input']>;
}>;


export type ChatMessagesQuery = { __typename?: 'Query', chatMessages: Array<{ __typename?: 'ChatMessage', id: string, body: string, createdAt: string, source: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } | null }> };

export type SendChatMessageMutationVariables = Exact<{
  body?: InputMaybe<Scalars['String']['input']>;
  nodeId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type SendChatMessageMutation = { __typename?: 'Mutation', sendChatMessage: { __typename?: 'ChatMessage', id: string, body: string, createdAt: string, source: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } | null } };

export type DeleteChatMessageMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type DeleteChatMessageMutation = { __typename?: 'Mutation', deleteChatMessage: boolean };

export type EnsureChatFolderMutationVariables = Exact<{ [key: string]: never; }>;


export type EnsureChatFolderMutation = { __typename?: 'Mutation', ensureChatFolder: { __typename?: 'Node', id: string, parentId?: string | null, name: string } };

export type ChatFolderInfoQueryVariables = Exact<{ [key: string]: never; }>;


export type ChatFolderInfoQuery = { __typename?: 'Query', chatFolder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null };

export type ClipFieldsFragment = { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null };

export type ClipsQueryVariables = Exact<{
  unreadOnly?: InputMaybe<Scalars['Boolean']['input']>;
  site?: InputMaybe<Scalars['String']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type ClipsQuery = { __typename?: 'Query', clips: { __typename?: 'ClipPage', nextCursor?: string | null, items: Array<{ __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null }> } };

export type ClipSitesQueryVariables = Exact<{ [key: string]: never; }>;


export type ClipSitesQuery = { __typename?: 'Query', clipSites: Array<{ __typename?: 'ClipSite', site: string, total: number }> };

export type ClipQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type ClipQuery = { __typename?: 'Query', clip: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null } };

export type NodeClipQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type NodeClipQuery = { __typename?: 'Query', nodeClip?: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null } | null };

export type ClipPrefsQueryVariables = Exact<{ [key: string]: never; }>;


export type ClipPrefsQuery = { __typename?: 'Query', clipPrefs: { __typename?: 'ClipPrefs', folder: string, byMonth: boolean } };

export type ClipUrlMutationVariables = Exact<{
  url: Scalars['String']['input'];
  html?: InputMaybe<Scalars['String']['input']>;
  force?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type ClipUrlMutation = { __typename?: 'Mutation', clipUrl: { __typename?: 'ClipResult', job?: { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null } | null, existing?: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null } | null } };

export type MarkClipReadMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  read?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type MarkClipReadMutation = { __typename?: 'Mutation', markClipRead: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null, assets?: { __typename?: 'Node', id: string, name: string } | null } };

export type SetClipPrefsMutationVariables = Exact<{
  input: ClipPrefsInput;
}>;


export type SetClipPrefsMutation = { __typename?: 'Mutation', setClipPrefs: { __typename?: 'ClipPrefs', folder: string, byMonth: boolean } };

export type FileRequestFieldsFragment = { __typename?: 'FileRequest', id: string, token: string, title: string, note: string, hasPassword: boolean, requireName: boolean, groupByUploader: boolean, maxFileSize?: number | null, maxTotalBytes?: number | null, maxFiles?: number | null, expiresAt?: string | null, createdAt: string, fileCount: number, totalBytes: number, uploaderCount: number, newCount: number, lastUploadAt?: string | null, folder: { __typename?: 'Node', id: string, name: string, parentId?: string | null } };

export type MyFileRequestsQueryVariables = Exact<{ [key: string]: never; }>;


export type MyFileRequestsQuery = { __typename?: 'Query', myFileRequests: Array<{ __typename?: 'FileRequest', id: string, token: string, title: string, note: string, hasPassword: boolean, requireName: boolean, groupByUploader: boolean, maxFileSize?: number | null, maxTotalBytes?: number | null, maxFiles?: number | null, expiresAt?: string | null, createdAt: string, fileCount: number, totalBytes: number, uploaderCount: number, newCount: number, lastUploadAt?: string | null, folder: { __typename?: 'Node', id: string, name: string, parentId?: string | null } }> };

export type CreateFileRequestMutationVariables = Exact<{
  folderId: Scalars['ID']['input'];
  input: FileRequestInput;
  password?: InputMaybe<Scalars['String']['input']>;
}>;


export type CreateFileRequestMutation = { __typename?: 'Mutation', createFileRequest: { __typename?: 'FileRequest', id: string, token: string, title: string, note: string, hasPassword: boolean, requireName: boolean, groupByUploader: boolean, maxFileSize?: number | null, maxTotalBytes?: number | null, maxFiles?: number | null, expiresAt?: string | null, createdAt: string, fileCount: number, totalBytes: number, uploaderCount: number, newCount: number, lastUploadAt?: string | null, folder: { __typename?: 'Node', id: string, name: string, parentId?: string | null } } };

export type UpdateFileRequestMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  input: FileRequestInput;
}>;


export type UpdateFileRequestMutation = { __typename?: 'Mutation', updateFileRequest: { __typename?: 'FileRequest', id: string, token: string, title: string, note: string, hasPassword: boolean, requireName: boolean, groupByUploader: boolean, maxFileSize?: number | null, maxTotalBytes?: number | null, maxFiles?: number | null, expiresAt?: string | null, createdAt: string, fileCount: number, totalBytes: number, uploaderCount: number, newCount: number, lastUploadAt?: string | null, folder: { __typename?: 'Node', id: string, name: string, parentId?: string | null } } };

export type RevokeFileRequestMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeFileRequestMutation = { __typename?: 'Mutation', revokeFileRequest: boolean };

export type MarkFileRequestSeenMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type MarkFileRequestSeenMutation = { __typename?: 'Mutation', markFileRequestSeen: boolean };

export type FileRequestUploadsQueryVariables = Exact<{
  id: Scalars['ID']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type FileRequestUploadsQuery = { __typename?: 'Query', fileRequestUploads: { __typename?: 'FileRequestUploadPage', nextCursor?: string | null, items: Array<{ __typename?: 'FileRequestUpload', id: string, fileName: string, size: number, uploaderName: string, ip?: string | null, userAgent?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null }> } };

export type FileRequestInfoQueryVariables = Exact<{
  token: Scalars['String']['input'];
}>;


export type FileRequestInfoQuery = { __typename?: 'Query', fileRequestInfo: { __typename?: 'FileRequestInfo', title: string, note: string, needPassword: boolean, needName: boolean, maxFileSize?: number | null, expiresAt?: string | null, closed: boolean } };

export type OpenFileRequestMutationVariables = Exact<{
  token: Scalars['String']['input'];
  password?: InputMaybe<Scalars['String']['input']>;
  uploaderName?: InputMaybe<Scalars['String']['input']>;
}>;


export type OpenFileRequestMutation = { __typename?: 'Mutation', openFileRequest: { __typename?: 'CollectAuth', accessToken: string } };

export type CollectSessionFieldsFragment = { __typename?: 'UploadSession', id: string, partSize: number, uploadedParts: Array<number>, expiresAt: string, status: string, partUrls: Array<{ __typename?: 'PartUrl', partNumber: number, url: string }> };

export type CollectInitUploadMutationVariables = Exact<{
  name: Scalars['String']['input'];
  size: Scalars['Int64']['input'];
}>;


export type CollectInitUploadMutation = { __typename?: 'Mutation', collectInitUpload: { __typename?: 'UploadSession', id: string, partSize: number, uploadedParts: Array<number>, expiresAt: string, status: string, partUrls: Array<{ __typename?: 'PartUrl', partNumber: number, url: string }> } };

export type CollectUploadSessionQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type CollectUploadSessionQuery = { __typename?: 'Query', collectUploadSession: { __typename?: 'UploadSession', id: string, partSize: number, uploadedParts: Array<number>, expiresAt: string, status: string, partUrls: Array<{ __typename?: 'PartUrl', partNumber: number, url: string }> } };

export type CollectCompleteUploadMutationVariables = Exact<{
  sessionId: Scalars['ID']['input'];
  etags: Array<PartEtag> | PartEtag;
}>;


export type CollectCompleteUploadMutation = { __typename?: 'Mutation', collectCompleteUpload: { __typename?: 'CollectUpload', name: string, size: number, createdAt: string } };

export type CollectAbortUploadMutationVariables = Exact<{
  sessionId: Scalars['ID']['input'];
}>;


export type CollectAbortUploadMutation = { __typename?: 'Mutation', collectAbortUpload: boolean };

export type CollectMyUploadsQueryVariables = Exact<{ [key: string]: never; }>;


export type CollectMyUploadsQuery = { __typename?: 'Query', collectMyUploads: Array<{ __typename?: 'CollectUpload', name: string, size: number, createdAt: string }> };

export type FeedFieldsFragment = { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null };

export type FeedItemFieldsFragment = { __typename?: 'FeedItem', id: string, title: string, url: string, enclosureUrl: string, enclosureType: string, enclosureSize?: number | null, publishedAt?: string | null, state: FeedItemState, error?: string | null, jobId?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null };

export type FeedsQueryVariables = Exact<{ [key: string]: never; }>;


export type FeedsQuery = { __typename?: 'Query', feeds: Array<{ __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null }> };

export type FeedQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type FeedQuery = { __typename?: 'Query', feed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type FeedItemsQueryVariables = Exact<{
  feedId: Scalars['ID']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type FeedItemsQuery = { __typename?: 'Query', feedItems: { __typename?: 'FeedItemPage', nextCursor?: string | null, items: Array<{ __typename?: 'FeedItem', id: string, title: string, url: string, enclosureUrl: string, enclosureType: string, enclosureSize?: number | null, publishedAt?: string | null, state: FeedItemState, error?: string | null, jobId?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null }> } };

export type PreviewFeedQueryVariables = Exact<{
  url: Scalars['String']['input'];
  assetPattern?: InputMaybe<Scalars['String']['input']>;
  prerelease?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type PreviewFeedQuery = { __typename?: 'Query', previewFeed: { __typename?: 'FeedPreview', kind: FeedKind, url: string, title: string, siteUrl: string, podcast: boolean, suggestedMode: FeedMode, items: Array<{ __typename?: 'FeedPreviewItem', title: string, url: string, enclosureUrl: string, enclosureSize?: number | null, publishedAt?: string | null }> } };

export type ExportOpmlQueryVariables = Exact<{ [key: string]: never; }>;


export type ExportOpmlQuery = { __typename?: 'Query', exportOpml: string };

export type CreateFeedMutationVariables = Exact<{
  input: FeedInput;
}>;


export type CreateFeedMutation = { __typename?: 'Mutation', createFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type UpdateFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  input: FeedInput;
}>;


export type UpdateFeedMutation = { __typename?: 'Mutation', updateFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type DeleteFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type DeleteFeedMutation = { __typename?: 'Mutation', deleteFeed: boolean };

export type RefreshFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RefreshFeedMutation = { __typename?: 'Mutation', refreshFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, folderPath?: string | null, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type FetchFeedItemMutationVariables = Exact<{
  itemId: Scalars['ID']['input'];
  mode: FeedMode;
}>;


export type FetchFeedItemMutation = { __typename?: 'Mutation', fetchFeedItem: { __typename?: 'FeedItem', id: string, title: string, url: string, enclosureUrl: string, enclosureType: string, enclosureSize?: number | null, publishedAt?: string | null, state: FeedItemState, error?: string | null, jobId?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } };

export type ImportOpmlMutationVariables = Exact<{
  opml: Scalars['String']['input'];
}>;


export type ImportOpmlMutation = { __typename?: 'Mutation', importOpml: { __typename?: 'OpmlImportResult', added: number, existed: number, failed: Array<{ __typename?: 'OpmlFailure', url: string, reason: string }> } };

export type JobFieldsFragment = { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null };

export type MyJobsQueryVariables = Exact<{
  active?: InputMaybe<Scalars['Boolean']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type MyJobsQuery = { __typename?: 'Query', myJobs: { __typename?: 'JobPage', nextCursor?: string | null, items: Array<{ __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null }> } };

export type JobQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type JobQuery = { __typename?: 'Query', job: { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null } };

export type FetchEnabledQueryVariables = Exact<{ [key: string]: never; }>;


export type FetchEnabledQuery = { __typename?: 'Query', fetchEnabled: boolean };

export type FetchUrlsMutationVariables = Exact<{
  urls: Array<Scalars['String']['input']> | Scalars['String']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type FetchUrlsMutation = { __typename?: 'Mutation', fetchUrls: Array<{ __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null }> };

export type CancelJobMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type CancelJobMutation = { __typename?: 'Mutation', cancelJob: { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null } };

export type RetryJobMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RetryJobMutation = { __typename?: 'Mutation', retryJob: { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null } };

export type ClearFinishedJobsMutationVariables = Exact<{ [key: string]: never; }>;


export type ClearFinishedJobsMutation = { __typename?: 'Mutation', clearFinishedJobs: number };

export type LinkUsageFieldsFragment = { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number };

export type DirectLinkFieldsFragment = { __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } };

export type LinksEnabledQueryVariables = Exact<{ [key: string]: never; }>;


export type LinksEnabledQuery = { __typename?: 'Query', linksEnabled: boolean };

export type MyLinksQueryVariables = Exact<{
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type MyLinksQuery = { __typename?: 'Query', myLinks: { __typename?: 'DirectLinkPage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } }> } };

export type LinkQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type LinkQuery = { __typename?: 'Query', link: { __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } } };

export type LinkStatsQueryVariables = Exact<{
  id: Scalars['ID']['input'];
  hours?: InputMaybe<Scalars['Int']['input']>;
}>;


export type LinkStatsQuery = { __typename?: 'Query', linkStats: Array<{ __typename?: 'LinkHourStat', hour: string, hits: number, bytes: number, blocked: number, ips: number }> };

export type LinkReferersQueryVariables = Exact<{
  id: Scalars['ID']['input'];
  days?: InputMaybe<Scalars['Int']['input']>;
}>;


export type LinkReferersQuery = { __typename?: 'Query', linkReferers: Array<{ __typename?: 'LinkReferer', host: string, hits: number, bytes: number }> };

export type LinkHitsQueryVariables = Exact<{
  id: Scalars['ID']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type LinkHitsQuery = { __typename?: 'Query', linkHits: { __typename?: 'LinkHitPage', nextCursor?: string | null, items: Array<{ __typename?: 'LinkHit', at: string, ip: string, userAgent: string, referer: string, status: number, bytes: number }> } };

export type LinkPrefsFieldsFragment = { __typename?: 'LinkPrefs', refererHosts: Array<string>, allowNoReferer: boolean, imageFolder: string, stripGps: boolean, dailyBytesCapDefault: number, monthlyBytesCap: number, monthlyBytesUsed: number };

export type LinkPrefsQueryVariables = Exact<{ [key: string]: never; }>;


export type LinkPrefsQuery = { __typename?: 'Query', linkPrefs: { __typename?: 'LinkPrefs', refererHosts: Array<string>, allowNoReferer: boolean, imageFolder: string, stripGps: boolean, dailyBytesCapDefault: number, monthlyBytesCap: number, monthlyBytesUsed: number } };

export type CreateLinksMutationVariables = Exact<{
  nodeIds: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
}>;


export type CreateLinksMutation = { __typename?: 'Mutation', createLinks: Array<{ __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } }> };

export type UpdateLinkMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  input: DirectLinkInput;
}>;


export type UpdateLinkMutation = { __typename?: 'Mutation', updateLink: { __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } } };

export type PauseLinkMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  paused: Scalars['Boolean']['input'];
}>;


export type PauseLinkMutation = { __typename?: 'Mutation', pauseLink: { __typename?: 'DirectLink', id: string, token: string, path: string, url: string, nodeId: string, name: string, mime?: string | null, size?: number | null, status: LinkStatus, pauseReason?: string | null, source: string, createdAt: string, expiresAt?: string | null, refererHosts?: Array<string> | null, allowNoReferer?: boolean | null, dailyBytesCap?: number | null, autoPause: boolean, hasGps: boolean, hitsTotal: number, bytesTotal: number, lastHitAt?: string | null, today: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number }, last30Days: { __typename?: 'LinkUsage', hits: number, bytes: number, blocked: number } } };

export type RevokeLinkMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeLinkMutation = { __typename?: 'Mutation', revokeLink: boolean };

export type SetLinkPrefsMutationVariables = Exact<{
  input: LinkPrefsInput;
}>;


export type SetLinkPrefsMutation = { __typename?: 'Mutation', setLinkPrefs: { __typename?: 'LinkPrefs', refererHosts: Array<string>, allowNoReferer: boolean, imageFolder: string, stripGps: boolean, dailyBytesCapDefault: number, monthlyBytesCap: number, monthlyBytesUsed: number } };

export type AdminLinkStatsQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminLinkStatsQuery = { __typename?: 'Query', adminLinkStats: { __typename?: 'AdminLinkStats', monthHits: number, monthBytes: number, monthlyBytesCap: number, top: Array<{ __typename?: 'AdminLink', id: string, owner: string, name: string, path: string, status: LinkStatus, monthHits: number, monthBytes: number }> } };

export type AdminPauseLinkMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  paused: Scalars['Boolean']['input'];
}>;


export type AdminPauseLinkMutation = { __typename?: 'Mutation', adminPauseLink: boolean };

export type AdminRevokeLinkMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type AdminRevokeLinkMutation = { __typename?: 'Mutation', adminRevokeLink: boolean };

export type NodeThumbQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodeThumbQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } };

export type McpapiKeysQueryVariables = Exact<{ [key: string]: never; }>;


export type McpapiKeysQuery = { __typename?: 'Query', mcpAPIKeys: Array<{ __typename?: 'MCPAPIKey', id: string, name: string, scopes: Array<string>, createdAt: string, lastUsedAt?: string | null }> };

export type CreateMcpapiKeyMutationVariables = Exact<{
  name: Scalars['String']['input'];
  scopes: Array<Scalars['String']['input']> | Scalars['String']['input'];
}>;


export type CreateMcpapiKeyMutation = { __typename?: 'Mutation', createMCPAPIKey: { __typename?: 'MCPAPIKeyCreated', token: string, credential: { __typename?: 'MCPAPIKey', id: string, name: string, scopes: Array<string>, createdAt: string, lastUsedAt?: string | null } } };

export type RevokeMcpapiKeyMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeMcpapiKeyMutation = { __typename?: 'Mutation', revokeMCPAPIKey: boolean };

export type OAuthGrantsQueryVariables = Exact<{ [key: string]: never; }>;


export type OAuthGrantsQuery = { __typename?: 'Query', oauthGrants: Array<{ __typename?: 'OAuthGrant', id: string, clientId: string, clientName: string, scopes: Array<string>, createdAt: string, updatedAt: string }> };

export type RevokeOAuthGrantMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeOAuthGrantMutation = { __typename?: 'Mutation', revokeOAuthGrant: boolean };

export type OAuthAuthorizationRequestQueryVariables = Exact<{
  input: OAuthAuthorizationInput;
}>;


export type OAuthAuthorizationRequestQuery = { __typename?: 'Query', oauthAuthorizationRequest: { __typename?: 'OAuthAuthorizationRequest', clientName: string, scopes: Array<string> } };

export type DecideOAuthAuthorizationMutationVariables = Exact<{
  input: OAuthAuthorizationInput;
  approved: Scalars['Boolean']['input'];
}>;


export type DecideOAuthAuthorizationMutation = { __typename?: 'Mutation', decideOAuthAuthorization: { __typename?: 'OAuthAuthorizationResult', redirectUrl: string } };

export type McpAccessRootsQueryVariables = Exact<{ [key: string]: never; }>;


export type McpAccessRootsQuery = { __typename?: 'Query', mcpAccessRoots: Array<{ __typename?: 'MCPAccessRoot', credentialId: string, credentialType: string, rootId: string, rootName: string }> };

export type SetMcpAccessRootMutationVariables = Exact<{
  credentialId: Scalars['ID']['input'];
  credentialType: Scalars['String']['input'];
  rootPath?: InputMaybe<Scalars['String']['input']>;
}>;


export type SetMcpAccessRootMutation = { __typename?: 'Mutation', setMCPAccessRoot: boolean };

export type McpAuditQueryVariables = Exact<{
  beforeId?: InputMaybe<Scalars['Int64']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type McpAuditQuery = { __typename?: 'Query', mcpAudit: Array<{ __typename?: 'MCPAuditEvent', id: number, credentialId: string, credentialType: string, endpoint: string, tool: string, status: string, errorCode?: string | null, createdAt: string }> };

export type PlayProgressFieldsFragment = { __typename?: 'PlayProgress', position: number, duration: number, finished: boolean, updatedAt: string };

export type PlaylistQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type PlaylistQuery = { __typename?: 'Query', playlist: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind } }> };

export type RecentPlaysQueryVariables = Exact<{
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type RecentPlaysQuery = { __typename?: 'Query', recentPlays: Array<{ __typename?: 'RecentPlay', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }, progress: { __typename?: 'PlayProgress', position: number, duration: number, finished: boolean, updatedAt: string } }> };

export type SaveProgressMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
  position: Scalars['Float']['input'];
  duration: Scalars['Float']['input'];
}>;


export type SaveProgressMutation = { __typename?: 'Mutation', saveProgress?: { __typename?: 'PlayProgress', position: number, duration: number, finished: boolean, updatedAt: string } | null };

export type ClearProgressMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type ClearProgressMutation = { __typename?: 'Mutation', clearProgress: boolean };

export type ChildrenQueryVariables = Exact<{
  parentId?: InputMaybe<Scalars['ID']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
  order?: InputMaybe<NodeOrder>;
  desc?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type ChildrenQuery = { __typename?: 'Query', children: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string, starred: boolean, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

export type NodePreviewQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodePreviewQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null, largeUrl?: string | null, contentUrl?: string | null, status?: TaskStatus | null, durationSec?: number | null, markdown: boolean, subtitles: Array<{ __typename?: 'SubtitleTrack', nodeId: string, name: string, label: string, lang: string, default: boolean }> }, playProgress?: { __typename?: 'PlayProgress', position: number, duration: number, finished: boolean, updatedAt: string } | null } };

export type RequestPreviewMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type RequestPreviewMutation = { __typename?: 'Mutation', requestPreview: { __typename?: 'PreviewInfo', kind: PreviewKind, status?: TaskStatus | null, contentUrl?: string | null } };

export type NodeQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodeQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type NodeDetailQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodeDetailQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, sha256?: string | null, createdAt: string, updatedAt: string, starred: boolean, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } };

export type CreateFolderMutationVariables = Exact<{
  parentId?: InputMaybe<Scalars['ID']['input']>;
  name: Scalars['String']['input'];
}>;


export type CreateFolderMutation = { __typename?: 'Mutation', createFolder: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type RenameNodeMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  name: Scalars['String']['input'];
}>;


export type RenameNodeMutation = { __typename?: 'Mutation', renameNode: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type MoveNodesMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
  targetParentId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type MoveNodesMutation = { __typename?: 'Mutation', moveNodes: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string }> };

export type DeleteNodesMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
}>;


export type DeleteNodesMutation = { __typename?: 'Mutation', deleteNodes: boolean };

export type CopyNodesMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
  targetParentId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type CopyNodesMutation = { __typename?: 'Mutation', copyNodes: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string }> };

export type SearchNodesQueryVariables = Exact<{
  q: Scalars['String']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type SearchNodesQuery = { __typename?: 'Query', searchNodes: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string, path?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

export type SearchContentQueryVariables = Exact<{
  q: Scalars['String']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type SearchContentQuery = { __typename?: 'Query', searchContent: { __typename?: 'ContentHitPage', nextCursor?: string | null, items: Array<{ __typename?: 'ContentHit', snippet: string, page?: number | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }, highlights: Array<{ __typename?: 'TextRange', start: number, length: number }> }> } };

export type QuickNodeFragment = { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string, openedAt?: string | null, starred: boolean, path?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } };

export type StarredNodesQueryVariables = Exact<{ [key: string]: never; }>;


export type StarredNodesQuery = { __typename?: 'Query', starredNodes: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string, openedAt?: string | null, starred: boolean, path?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> };

export type RecentFilesQueryVariables = Exact<{ [key: string]: never; }>;


export type RecentFilesQuery = { __typename?: 'Query', recentFiles: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string, openedAt?: string | null, starred: boolean, path?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> };

export type SetStarredMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
  starred: Scalars['Boolean']['input'];
}>;


export type SetStarredMutation = { __typename?: 'Mutation', setStarred: Array<{ __typename?: 'Node', id: string, starred: boolean }> };

export type MarkOpenedMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type MarkOpenedMutation = { __typename?: 'Mutation', markOpened: boolean };

export type ClearRecentMutationVariables = Exact<{
  ids?: InputMaybe<Array<Scalars['ID']['input']> | Scalars['ID']['input']>;
}>;


export type ClearRecentMutation = { __typename?: 'Mutation', clearRecent: boolean };

export type FileVersionsQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type FileVersionsQuery = { __typename?: 'Query', fileVersions: { __typename?: 'FileVersionList', keep: number, keepDays: number, items: Array<{ __typename?: 'FileVersion', id: string, size: number, savedAt: string, replacedAt: string, downloadUrl: string }> } };

export type RestoreFileVersionMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RestoreFileVersionMutation = { __typename?: 'Mutation', restoreFileVersion: { __typename?: 'Node', id: string, size?: number | null, updatedAt: string } };

export type DeleteFileVersionsMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
}>;


export type DeleteFileVersionsMutation = { __typename?: 'Mutation', deleteFileVersions: boolean };

export type PurgeFileVersionsMutationVariables = Exact<{
  nodeId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type PurgeFileVersionsMutation = { __typename?: 'Mutation', purgeFileVersions: number };

export type TextFileQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type TextFileQuery = { __typename?: 'Query', textFile: { __typename?: 'TextFile', content: string, sha256: string, size: number } };

export type SaveTextFileMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
  content: Scalars['String']['input'];
  baseSha256?: InputMaybe<Scalars['String']['input']>;
}>;


export type SaveTextFileMutation = { __typename?: 'Mutation', saveTextFile: { __typename?: 'Node', id: string, size?: number | null, sha256?: string | null, updatedAt: string } };

export type CreateTextFileMutationVariables = Exact<{
  parentId?: InputMaybe<Scalars['ID']['input']>;
  name: Scalars['String']['input'];
  content?: InputMaybe<Scalars['String']['input']>;
}>;


export type CreateTextFileMutation = { __typename?: 'Mutation', createTextFile: { __typename?: 'Node', id: string, name: string } };

export type NotificationsQueryVariables = Exact<{
  cursor?: InputMaybe<Scalars['String']['input']>;
  unreadOnly?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type NotificationsQuery = { __typename?: 'Query', notifications: { __typename?: 'NotificationPage', nextCursor?: string | null, items: Array<{ __typename?: 'Notification', id: string, kind: string, level: NotificationLevel, title: string, body: string, count: number, createdAt: string, updatedAt: string, read: boolean, target?: { __typename?: 'NotificationTarget', kind: NotificationTargetKind, id?: string | null, parentId?: string | null } | null }> } };

export type MarkNotificationsReadMutationVariables = Exact<{
  ids?: InputMaybe<Array<Scalars['ID']['input']> | Scalars['ID']['input']>;
}>;


export type MarkNotificationsReadMutation = { __typename?: 'Mutation', markNotificationsRead: number };

export type ClearNotificationsMutationVariables = Exact<{ [key: string]: never; }>;


export type ClearNotificationsMutation = { __typename?: 'Mutation', clearNotifications: number };

export type NotifyChannelFieldsFragment = { __typename?: 'NotifyChannel', id: string, kind: NotifyChannelKind, name: string, enabled: boolean, events: Array<string>, url: string, topic: string, hasSecret: boolean, secretHint: string, lastOkAt?: string | null, lastError?: string | null, lastErrorAt?: string | null, createdAt: string };

export type NotifyChannelsQueryVariables = Exact<{ [key: string]: never; }>;


export type NotifyChannelsQuery = { __typename?: 'Query', notifyChannels: Array<{ __typename?: 'NotifyChannel', id: string, kind: NotifyChannelKind, name: string, enabled: boolean, events: Array<string>, url: string, topic: string, hasSecret: boolean, secretHint: string, lastOkAt?: string | null, lastError?: string | null, lastErrorAt?: string | null, createdAt: string }> };

export type NotifyEventKindsQueryVariables = Exact<{ [key: string]: never; }>;


export type NotifyEventKindsQuery = { __typename?: 'Query', notifyEventKinds: Array<{ __typename?: 'NotifyEventKind', kind: string, label: string, defaultPush: boolean, adminOnly: boolean }> };

export type CreateNotifyChannelMutationVariables = Exact<{
  input: NotifyChannelInput;
}>;


export type CreateNotifyChannelMutation = { __typename?: 'Mutation', createNotifyChannel: { __typename?: 'NotifyChannel', id: string, kind: NotifyChannelKind, name: string, enabled: boolean, events: Array<string>, url: string, topic: string, hasSecret: boolean, secretHint: string, lastOkAt?: string | null, lastError?: string | null, lastErrorAt?: string | null, createdAt: string } };

export type UpdateNotifyChannelMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  input: NotifyChannelInput;
}>;


export type UpdateNotifyChannelMutation = { __typename?: 'Mutation', updateNotifyChannel: { __typename?: 'NotifyChannel', id: string, kind: NotifyChannelKind, name: string, enabled: boolean, events: Array<string>, url: string, topic: string, hasSecret: boolean, secretHint: string, lastOkAt?: string | null, lastError?: string | null, lastErrorAt?: string | null, createdAt: string } };

export type DeleteNotifyChannelMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type DeleteNotifyChannelMutation = { __typename?: 'Mutation', deleteNotifyChannel: boolean };

export type TestNotifyChannelMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type TestNotifyChannelMutation = { __typename?: 'Mutation', testNotifyChannel: boolean };

export type SessionsQueryVariables = Exact<{ [key: string]: never; }>;


export type SessionsQuery = { __typename?: 'Query', sessions: Array<{ __typename?: 'Session', familyId: string, userAgent: string, ip: string, createdAt: string, lastSeenAt: string, revokedAt?: string | null, active: boolean, current: boolean }> };

export type RevokeSessionMutationVariables = Exact<{
  familyId: Scalars['ID']['input'];
}>;


export type RevokeSessionMutation = { __typename?: 'Mutation', revokeSession: boolean };

export type CreateShareMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
  password?: InputMaybe<Scalars['String']['input']>;
  expiresAt?: InputMaybe<Scalars['Time']['input']>;
}>;


export type CreateShareMutation = { __typename?: 'Mutation', createShare: { __typename?: 'Share', id: string, token: string, hasPassword: boolean, expiresAt?: string | null, createdAt: string, node: { __typename?: 'Node', id: string, name: string, kind: NodeKind } } };

export type RevokeShareMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RevokeShareMutation = { __typename?: 'Mutation', revokeShare: boolean };

export type MySharesQueryVariables = Exact<{ [key: string]: never; }>;


export type MySharesQuery = { __typename?: 'Query', myShares: Array<{ __typename?: 'Share', id: string, token: string, hasPassword: boolean, expiresAt?: string | null, createdAt: string, visitStats: { __typename?: 'ShareVisitStats', verifyCount: number, downloadCount: number, lastVisitAt?: string | null, lastVisitKind?: string | null }, node: { __typename?: 'Node', id: string, name: string, kind: NodeKind } }> };

export type ShareVisitsQueryVariables = Exact<{
  shareId: Scalars['ID']['input'];
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type ShareVisitsQuery = { __typename?: 'Query', shareVisits: Array<{ __typename?: 'ShareVisit', kind: string, ip?: string | null, userAgent?: string | null, createdAt: string }> };

export type ShareInfoQueryVariables = Exact<{
  token: Scalars['String']['input'];
}>;


export type ShareInfoQuery = { __typename?: 'Query', shareInfo: { __typename?: 'ShareInfo', token: string, name: string, kind: NodeKind, needPassword: boolean, expired: boolean, expiresAt?: string | null, size?: number | null, fileCount?: number | null } };

export type VerifySharePasswordMutationVariables = Exact<{
  token: Scalars['String']['input'];
  password: Scalars['String']['input'];
}>;


export type VerifySharePasswordMutation = { __typename?: 'Mutation', verifySharePassword: { __typename?: 'ShareAuth', accessToken: string } };

export type ShareRootQueryVariables = Exact<{ [key: string]: never; }>;


export type ShareRootQuery = { __typename?: 'Query', shareRoot: { __typename?: 'Node', id: string, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type SpaceFileFieldsFragment = { __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } };

export type SpaceOverviewQueryVariables = Exact<{ [key: string]: never; }>;


export type SpaceOverviewQuery = { __typename?: 'Query', spaceOverview: { __typename?: 'SpaceOverview', usedBytes: number, quotaBytes: number, trashFiles: number, trashBytes: number, duplicateGroups: number, duplicateBytes: number, versionFiles: number, versionBytes: number, byType: Array<{ __typename?: 'SpaceCategory', category: FileCategory, files: number, bytes: number }> } };

export type SpaceFoldersQueryVariables = Exact<{
  parentId?: InputMaybe<Scalars['ID']['input']>;
}>;


export type SpaceFoldersQuery = { __typename?: 'Query', spaceFolders: { __typename?: 'SpaceFolderLevel', path: string, looseFiles: number, looseBytes: number, folders: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string }> } };

export type SpaceLargeFilesQueryVariables = Exact<{
  category?: InputMaybe<FileCategory>;
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type SpaceLargeFilesQuery = { __typename?: 'Query', spaceLargeFiles: Array<{ __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } }> };

export type SpaceStaleFilesQueryVariables = Exact<{
  days?: InputMaybe<Scalars['Int']['input']>;
  minSize?: InputMaybe<Scalars['Int64']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type SpaceStaleFilesQuery = { __typename?: 'Query', spaceStaleFiles: Array<{ __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } }> };

export type SpaceDuplicatesQueryVariables = Exact<{
  offset?: InputMaybe<Scalars['Int']['input']>;
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type SpaceDuplicatesQuery = { __typename?: 'Query', spaceDuplicates: { __typename?: 'DuplicatePage', nextOffset?: number | null, items: Array<{ __typename?: 'DuplicateGroup', sha256: string, size: number, copies: number, files: Array<{ __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } }> }> } };

export type SpaceEmptyFoldersQueryVariables = Exact<{
  limit?: InputMaybe<Scalars['Int']['input']>;
}>;


export type SpaceEmptyFoldersQuery = { __typename?: 'Query', spaceEmptyFolders: Array<{ __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } }> };

export type SpaceIdleQueryVariables = Exact<{ [key: string]: never; }>;


export type SpaceIdleQuery = { __typename?: 'Query', spaceIdle: { __typename?: 'SpaceIdle', shares: Array<{ __typename?: 'IdleShare', id: string, token: string, nodeId: string, name: string, kind: NodeKind, expiresAt?: string | null, createdAt: string, lastVisitAt?: string | null, expired: boolean }>, links: Array<{ __typename?: 'IdleLink', id: string, nodeId: string, name: string, expiresAt?: string | null, createdAt: string, lastHitAt?: string | null, hits: number, expired: boolean }>, requests: Array<{ __typename?: 'IdleRequest', id: string, title: string, folderId: string, expiresAt: string, createdAt: string }> } };

export type AdminStorageQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminStorageQuery = { __typename?: 'Query', adminStorage: { __typename?: 'AdminStorage', logicalBytes: number, physicalBytes: number, blobs: number, pendingGcBytes: number, derivativeBytes: number, derivatives: number, textIndexBytes: number, activityBytes: number } };

export type TrashQueryVariables = Exact<{
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type TrashQuery = { __typename?: 'Query', trash: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string, deletedAt?: string | null, purgeAt?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

export type RestoreNodesMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
}>;


export type RestoreNodesMutation = { __typename?: 'Mutation', restoreNodes: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string }> };

export type PurgeNodesMutationVariables = Exact<{
  ids: Array<Scalars['ID']['input']> | Scalars['ID']['input'];
}>;


export type PurgeNodesMutation = { __typename?: 'Mutation', purgeNodes: boolean };

export type PurgeTrashMutationVariables = Exact<{ [key: string]: never; }>;


export type PurgeTrashMutation = { __typename?: 'Mutation', purgeTrash: boolean };

export type InitUploadMutationVariables = Exact<{
  parentId?: InputMaybe<Scalars['ID']['input']>;
  name: Scalars['String']['input'];
  sha256: Scalars['String']['input'];
  size: Scalars['Int64']['input'];
  replace?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type InitUploadMutation = { __typename?: 'Mutation', initUpload: { __typename?: 'UploadInit', instant: boolean, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } | null, session?: { __typename?: 'UploadSession', id: string, partSize: number, uploadedParts: Array<number>, expiresAt: string, status: string, partUrls: Array<{ __typename?: 'PartUrl', partNumber: number, url: string }> } | null } };

export type CompleteUploadMutationVariables = Exact<{
  sessionId: Scalars['ID']['input'];
  etags: Array<PartEtag> | PartEtag;
}>;


export type CompleteUploadMutation = { __typename?: 'Mutation', completeUpload: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type AbortUploadMutationVariables = Exact<{
  sessionId: Scalars['ID']['input'];
}>;


export type AbortUploadMutation = { __typename?: 'Mutation', abortUpload: boolean };

export type UploadSessionQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type UploadSessionQuery = { __typename?: 'Query', uploadSession: { __typename?: 'UploadSession', id: string, partSize: number, uploadedParts: Array<number>, expiresAt: string, status: string, partUrls: Array<{ __typename?: 'PartUrl', partNumber: number, url: string }> } };

export type NodeDownloadUrlQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodeDownloadUrlQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, downloadUrl?: string | null } };

export class TypedDocumentString<TResult, TVariables>
  extends String
  implements DocumentTypeDecoration<TResult, TVariables>
{
  __apiType?: NonNullable<DocumentTypeDecoration<TResult, TVariables>['__apiType']>;
  private value: string;
  public __meta__?: Record<string, any> | undefined;

  constructor(value: string, __meta__?: Record<string, any> | undefined) {
    super(value);
    this.value = value;
    this.__meta__ = __meta__;
  }

  override toString(): string & DocumentTypeDecoration<TResult, TVariables> {
    return this.value;
  }
}
export const ActivityFieldsFragmentDoc = new TypedDocumentString(`
    fragment ActivityFields on Activity {
  id
  at
  actor {
    kind
    ref
    label
  }
  batchId
  action
  nodeId
  node {
    id
    parentId
    name
    kind
  }
  nodeKind
  name
  parentPath
  fromPath
  toPath
  oldName
  newName
  files
  bytes
  note
  undoOf
  undoneAt
  undoable
}
    `, {"fragmentName":"ActivityFields"}) as unknown as TypedDocumentString<ActivityFieldsFragment, unknown>;
export const UndoResultFieldsFragmentDoc = new TypedDocumentString(`
    fragment UndoResultFields on UndoResult {
  done
  failed
  items {
    activity {
      ...ActivityFields
    }
    ok
    reason
  }
}
    fragment ActivityFields on Activity {
  id
  at
  actor {
    kind
    ref
    label
  }
  batchId
  action
  nodeId
  node {
    id
    parentId
    name
    kind
  }
  nodeKind
  name
  parentPath
  fromPath
  toPath
  oldName
  newName
  files
  bytes
  note
  undoOf
  undoneAt
  undoable
}`, {"fragmentName":"UndoResultFields"}) as unknown as TypedDocumentString<UndoResultFieldsFragment, unknown>;
export const ClipFieldsFragmentDoc = new TypedDocumentString(`
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}
    `, {"fragmentName":"ClipFields"}) as unknown as TypedDocumentString<ClipFieldsFragment, unknown>;
export const FileRequestFieldsFragmentDoc = new TypedDocumentString(`
    fragment FileRequestFields on FileRequest {
  id
  token
  title
  note
  hasPassword
  requireName
  groupByUploader
  maxFileSize
  maxTotalBytes
  maxFiles
  expiresAt
  createdAt
  fileCount
  totalBytes
  uploaderCount
  newCount
  lastUploadAt
  folder {
    id
    name
    parentId
  }
}
    `, {"fragmentName":"FileRequestFields"}) as unknown as TypedDocumentString<FileRequestFieldsFragment, unknown>;
export const CollectSessionFieldsFragmentDoc = new TypedDocumentString(`
    fragment CollectSessionFields on UploadSession {
  id
  partSize
  partUrls {
    partNumber
    url
  }
  uploadedParts
  expiresAt
  status
}
    `, {"fragmentName":"CollectSessionFields"}) as unknown as TypedDocumentString<CollectSessionFieldsFragment, unknown>;
export const FeedFieldsFragmentDoc = new TypedDocumentString(`
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}
    `, {"fragmentName":"FeedFields"}) as unknown as TypedDocumentString<FeedFieldsFragment, unknown>;
export const FeedItemFieldsFragmentDoc = new TypedDocumentString(`
    fragment FeedItemFields on FeedItem {
  id
  title
  url
  enclosureUrl
  enclosureType
  enclosureSize
  publishedAt
  state
  error
  node {
    id
    parentId
    name
    kind
  }
  jobId
  createdAt
}
    `, {"fragmentName":"FeedItemFields"}) as unknown as TypedDocumentString<FeedItemFieldsFragment, unknown>;
export const JobFieldsFragmentDoc = new TypedDocumentString(`
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}
    `, {"fragmentName":"JobFields"}) as unknown as TypedDocumentString<JobFieldsFragment, unknown>;
export const LinkUsageFieldsFragmentDoc = new TypedDocumentString(`
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
    `, {"fragmentName":"LinkUsageFields"}) as unknown as TypedDocumentString<LinkUsageFieldsFragment, unknown>;
export const DirectLinkFieldsFragmentDoc = new TypedDocumentString(`
    fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}`, {"fragmentName":"DirectLinkFields"}) as unknown as TypedDocumentString<DirectLinkFieldsFragment, unknown>;
export const LinkPrefsFieldsFragmentDoc = new TypedDocumentString(`
    fragment LinkPrefsFields on LinkPrefs {
  refererHosts
  allowNoReferer
  imageFolder
  stripGps
  dailyBytesCapDefault
  monthlyBytesCap
  monthlyBytesUsed
}
    `, {"fragmentName":"LinkPrefsFields"}) as unknown as TypedDocumentString<LinkPrefsFieldsFragment, unknown>;
export const PlayProgressFieldsFragmentDoc = new TypedDocumentString(`
    fragment PlayProgressFields on PlayProgress {
  position
  duration
  finished
  updatedAt
}
    `, {"fragmentName":"PlayProgressFields"}) as unknown as TypedDocumentString<PlayProgressFieldsFragment, unknown>;
export const QuickNodeFragmentDoc = new TypedDocumentString(`
    fragment QuickNode on Node {
  id
  parentId
  name
  kind
  size
  subtreeBytes
  subtreeCount
  statsStale
  updatedAt
  openedAt
  starred
  path
  preview {
    kind
    thumbUrl
  }
}
    `, {"fragmentName":"QuickNode"}) as unknown as TypedDocumentString<QuickNodeFragment, unknown>;
export const NotifyChannelFieldsFragmentDoc = new TypedDocumentString(`
    fragment NotifyChannelFields on NotifyChannel {
  id
  kind
  name
  enabled
  events
  url
  topic
  hasSecret
  secretHint
  lastOkAt
  lastError
  lastErrorAt
  createdAt
}
    `, {"fragmentName":"NotifyChannelFields"}) as unknown as TypedDocumentString<NotifyChannelFieldsFragment, unknown>;
export const SpaceFileFieldsFragmentDoc = new TypedDocumentString(`
    fragment SpaceFileFields on SpaceFile {
  node {
    id
    parentId
    name
    kind
    size
    mime
    updatedAt
    preview {
      kind
      thumbUrl
    }
  }
  path
  category
}
    `, {"fragmentName":"SpaceFileFields"}) as unknown as TypedDocumentString<SpaceFileFieldsFragment, unknown>;
export const ActivitiesDocument = new TypedDocumentString(`
    query Activities($filter: ActivityFilter, $cursor: String, $limit: Int) {
  activities(filter: $filter, cursor: $cursor, limit: $limit) {
    items {
      ...ActivityFields
    }
    nextCursor
  }
}
    fragment ActivityFields on Activity {
  id
  at
  actor {
    kind
    ref
    label
  }
  batchId
  action
  nodeId
  node {
    id
    parentId
    name
    kind
  }
  nodeKind
  name
  parentPath
  fromPath
  toPath
  oldName
  newName
  files
  bytes
  note
  undoOf
  undoneAt
  undoable
}`) as unknown as TypedDocumentString<ActivitiesQuery, ActivitiesQueryVariables>;
export const ActivityActorsDocument = new TypedDocumentString(`
    query ActivityActors {
  activityActors {
    actor {
      kind
      ref
      label
    }
    total
    lastAt
  }
}
    `) as unknown as TypedDocumentString<ActivityActorsQuery, ActivityActorsQueryVariables>;
export const UndoPreviewDocument = new TypedDocumentString(`
    query UndoPreview($selection: UndoSelection!) {
  undoPreview(selection: $selection) {
    ...UndoResultFields
  }
}
    fragment ActivityFields on Activity {
  id
  at
  actor {
    kind
    ref
    label
  }
  batchId
  action
  nodeId
  node {
    id
    parentId
    name
    kind
  }
  nodeKind
  name
  parentPath
  fromPath
  toPath
  oldName
  newName
  files
  bytes
  note
  undoOf
  undoneAt
  undoable
}
fragment UndoResultFields on UndoResult {
  done
  failed
  items {
    activity {
      ...ActivityFields
    }
    ok
    reason
  }
}`) as unknown as TypedDocumentString<UndoPreviewQuery, UndoPreviewQueryVariables>;
export const UndoActivitiesDocument = new TypedDocumentString(`
    mutation UndoActivities($selection: UndoSelection!) {
  undoActivities(selection: $selection) {
    ...UndoResultFields
  }
}
    fragment ActivityFields on Activity {
  id
  at
  actor {
    kind
    ref
    label
  }
  batchId
  action
  nodeId
  node {
    id
    parentId
    name
    kind
  }
  nodeKind
  name
  parentPath
  fromPath
  toPath
  oldName
  newName
  files
  bytes
  note
  undoOf
  undoneAt
  undoable
}
fragment UndoResultFields on UndoResult {
  done
  failed
  items {
    activity {
      ...ActivityFields
    }
    ok
    reason
  }
}`) as unknown as TypedDocumentString<UndoActivitiesMutation, UndoActivitiesMutationVariables>;
export const AdminUsersDocument = new TypedDocumentString(`
    query AdminUsers {
  adminUsers {
    id
    username
    isAdmin
    disabled
    quotaBytes
    usedBytes
    createdAt
  }
}
    `) as unknown as TypedDocumentString<AdminUsersQuery, AdminUsersQueryVariables>;
export const AdminOverviewDocument = new TypedDocumentString(`
    query AdminOverview {
  adminOverview {
    userCount
    totalUsedBytes
    blobCount
    blobBytes
    taskCounts {
      status
      count
    }
    contentIndex {
      enabled
      done
      empty
      truncated
      unsupported
      failed
      pending
      textBytes
    }
  }
}
    `) as unknown as TypedDocumentString<AdminOverviewQuery, AdminOverviewQueryVariables>;
export const AdminCreateUserDocument = new TypedDocumentString(`
    mutation AdminCreateUser($username: String!, $password: String!, $quotaBytes: Int64) {
  adminCreateUser(
    username: $username
    password: $password
    quotaBytes: $quotaBytes
  ) {
    id
    username
    disabled
    quotaBytes
    usedBytes
  }
}
    `) as unknown as TypedDocumentString<AdminCreateUserMutation, AdminCreateUserMutationVariables>;
export const AdminSetQuotaDocument = new TypedDocumentString(`
    mutation AdminSetQuota($userId: ID!, $quotaBytes: Int64!) {
  adminSetQuota(userId: $userId, quotaBytes: $quotaBytes) {
    id
    quotaBytes
  }
}
    `) as unknown as TypedDocumentString<AdminSetQuotaMutation, AdminSetQuotaMutationVariables>;
export const AdminSetDisabledDocument = new TypedDocumentString(`
    mutation AdminSetDisabled($userId: ID!, $disabled: Boolean!) {
  adminSetDisabled(userId: $userId, disabled: $disabled) {
    id
    disabled
  }
}
    `) as unknown as TypedDocumentString<AdminSetDisabledMutation, AdminSetDisabledMutationVariables>;
export const AdminResetPasswordDocument = new TypedDocumentString(`
    mutation AdminResetPassword($userId: ID!) {
  adminResetPassword(userId: $userId)
}
    `) as unknown as TypedDocumentString<AdminResetPasswordMutation, AdminResetPasswordMutationVariables>;
export const AdminRetryFailedTasksDocument = new TypedDocumentString(`
    mutation AdminRetryFailedTasks {
  adminRetryFailedTasks
}
    `) as unknown as TypedDocumentString<AdminRetryFailedTasksMutation, AdminRetryFailedTasksMutationVariables>;
export const AppPasswordsDocument = new TypedDocumentString(`
    query AppPasswords {
  appPasswords {
    id
    name
    createdAt
    lastUsedAt
  }
}
    `) as unknown as TypedDocumentString<AppPasswordsQuery, AppPasswordsQueryVariables>;
export const CreateAppPasswordDocument = new TypedDocumentString(`
    mutation CreateAppPassword($name: String!) {
  createAppPassword(name: $name)
}
    `) as unknown as TypedDocumentString<CreateAppPasswordMutation, CreateAppPasswordMutationVariables>;
export const RevokeAppPasswordDocument = new TypedDocumentString(`
    mutation RevokeAppPassword($id: ID!) {
  revokeAppPassword(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeAppPasswordMutation, RevokeAppPasswordMutationVariables>;
export const ArchiveInfoDocument = new TypedDocumentString(`
    query ArchiveInfo($nodeId: ID!) {
  archiveInfo(nodeId: $nodeId) {
    status
    format
    entryCount
    fileCount
    totalSize
    solid
    encrypted
    error
  }
}
    `) as unknown as TypedDocumentString<ArchiveInfoQuery, ArchiveInfoQueryVariables>;
export const ArchiveEntriesDocument = new TypedDocumentString(`
    query ArchiveEntries($nodeId: ID!, $path: String, $cursor: String) {
  archiveEntries(nodeId: $nodeId, path: $path, cursor: $cursor) {
    items {
      idx
      path
      name
      isDir
      size
      modifiedAt
      flag
      previewable
      downloadable
    }
    nextCursor
    total
  }
}
    `) as unknown as TypedDocumentString<ArchiveEntriesQuery, ArchiveEntriesQueryVariables>;
export const ExtractArchiveDocument = new TypedDocumentString(`
    mutation ExtractArchive($nodeId: ID!, $base: String, $paths: [String!], $parentId: ID) {
  extractArchive(nodeId: $nodeId, base: $base, paths: $paths, parentId: $parentId) {
    id
    status
    title
  }
}
    `) as unknown as TypedDocumentString<ExtractArchiveMutation, ExtractArchiveMutationVariables>;
export const LoginDocument = new TypedDocumentString(`
    mutation Login($username: String!, $password: String!) {
  login(username: $username, password: $password) {
    accessToken
    user {
      id
      username
      quotaBytes
      usedBytes
      isAdmin
    }
  }
}
    `) as unknown as TypedDocumentString<LoginMutation, LoginMutationVariables>;
export const RegisterDocument = new TypedDocumentString(`
    mutation Register($username: String!, $password: String!) {
  register(username: $username, password: $password) {
    accessToken
    user {
      id
      username
      quotaBytes
      usedBytes
      isAdmin
    }
  }
}
    `) as unknown as TypedDocumentString<RegisterMutation, RegisterMutationVariables>;
export const RefreshDocument = new TypedDocumentString(`
    mutation Refresh {
  refresh {
    accessToken
    user {
      id
      username
      quotaBytes
      usedBytes
      isAdmin
    }
  }
}
    `) as unknown as TypedDocumentString<RefreshMutation, RefreshMutationVariables>;
export const LogoutDocument = new TypedDocumentString(`
    mutation Logout {
  logout
}
    `) as unknown as TypedDocumentString<LogoutMutation, LogoutMutationVariables>;
export const MeDocument = new TypedDocumentString(`
    query Me {
  me {
    id
    username
    quotaBytes
    usedBytes
    isAdmin
    unreadNotifications
  }
}
    `) as unknown as TypedDocumentString<MeQuery, MeQueryVariables>;
export const ChangePasswordDocument = new TypedDocumentString(`
    mutation ChangePassword($oldPassword: String!, $newPassword: String!) {
  changePassword(oldPassword: $oldPassword, newPassword: $newPassword) {
    accessToken
    user {
      id
      username
      quotaBytes
      usedBytes
      isAdmin
    }
  }
}
    `) as unknown as TypedDocumentString<ChangePasswordMutation, ChangePasswordMutationVariables>;
export const ChatMessagesDocument = new TypedDocumentString(`
    query ChatMessages($limit: Int, $before: ID, $after: ID, $query: String) {
  chatMessages(limit: $limit, before: $before, after: $after, query: $query) {
    id
    body
    createdAt
    source
    node {
      id
      parentId
      name
      kind
      size
      preview {
        kind
        thumbUrl
      }
    }
  }
}
    `) as unknown as TypedDocumentString<ChatMessagesQuery, ChatMessagesQueryVariables>;
export const SendChatMessageDocument = new TypedDocumentString(`
    mutation SendChatMessage($body: String, $nodeId: ID) {
  sendChatMessage(body: $body, nodeId: $nodeId) {
    id
    body
    createdAt
    source
    node {
      id
      parentId
      name
      kind
      size
      preview {
        kind
        thumbUrl
      }
    }
  }
}
    `) as unknown as TypedDocumentString<SendChatMessageMutation, SendChatMessageMutationVariables>;
export const DeleteChatMessageDocument = new TypedDocumentString(`
    mutation DeleteChatMessage($id: ID!) {
  deleteChatMessage(id: $id)
}
    `) as unknown as TypedDocumentString<DeleteChatMessageMutation, DeleteChatMessageMutationVariables>;
export const EnsureChatFolderDocument = new TypedDocumentString(`
    mutation EnsureChatFolder {
  ensureChatFolder {
    id
    parentId
    name
  }
}
    `) as unknown as TypedDocumentString<EnsureChatFolderMutation, EnsureChatFolderMutationVariables>;
export const ChatFolderInfoDocument = new TypedDocumentString(`
    query ChatFolderInfo {
  chatFolder {
    id
    parentId
    name
  }
}
    `) as unknown as TypedDocumentString<ChatFolderInfoQuery, ChatFolderInfoQueryVariables>;
export const ClipsDocument = new TypedDocumentString(`
    query Clips($unreadOnly: Boolean, $site: String, $cursor: String) {
  clips(unreadOnly: $unreadOnly, site: $site, cursor: $cursor) {
    items {
      ...ClipFields
    }
    nextCursor
  }
}
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}`) as unknown as TypedDocumentString<ClipsQuery, ClipsQueryVariables>;
export const ClipSitesDocument = new TypedDocumentString(`
    query ClipSites {
  clipSites {
    site
    total
  }
}
    `) as unknown as TypedDocumentString<ClipSitesQuery, ClipSitesQueryVariables>;
export const ClipDocument = new TypedDocumentString(`
    query Clip($id: ID!) {
  clip(id: $id) {
    ...ClipFields
  }
}
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}`) as unknown as TypedDocumentString<ClipQuery, ClipQueryVariables>;
export const NodeClipDocument = new TypedDocumentString(`
    query NodeClip($nodeId: ID!) {
  nodeClip(nodeId: $nodeId) {
    ...ClipFields
  }
}
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}`) as unknown as TypedDocumentString<NodeClipQuery, NodeClipQueryVariables>;
export const ClipPrefsDocument = new TypedDocumentString(`
    query ClipPrefs {
  clipPrefs {
    folder
    byMonth
  }
}
    `) as unknown as TypedDocumentString<ClipPrefsQuery, ClipPrefsQueryVariables>;
export const ClipUrlDocument = new TypedDocumentString(`
    mutation ClipUrl($url: String!, $html: String, $force: Boolean) {
  clipUrl(url: $url, html: $html, force: $force) {
    job {
      ...JobFields
    }
    existing {
      ...ClipFields
    }
  }
}
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}
fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<ClipUrlMutation, ClipUrlMutationVariables>;
export const MarkClipReadDocument = new TypedDocumentString(`
    mutation MarkClipRead($id: ID!, $read: Boolean) {
  markClipRead(id: $id, read: $read) {
    ...ClipFields
  }
}
    fragment ClipFields on Clip {
  id
  url
  title
  site
  byline
  excerpt
  publishedAt
  node {
    id
    parentId
    name
    kind
  }
  assets {
    id
    name
  }
  words
  images
  readAt
  createdAt
}`) as unknown as TypedDocumentString<MarkClipReadMutation, MarkClipReadMutationVariables>;
export const SetClipPrefsDocument = new TypedDocumentString(`
    mutation SetClipPrefs($input: ClipPrefsInput!) {
  setClipPrefs(input: $input) {
    folder
    byMonth
  }
}
    `) as unknown as TypedDocumentString<SetClipPrefsMutation, SetClipPrefsMutationVariables>;
export const MyFileRequestsDocument = new TypedDocumentString(`
    query MyFileRequests {
  myFileRequests {
    ...FileRequestFields
  }
}
    fragment FileRequestFields on FileRequest {
  id
  token
  title
  note
  hasPassword
  requireName
  groupByUploader
  maxFileSize
  maxTotalBytes
  maxFiles
  expiresAt
  createdAt
  fileCount
  totalBytes
  uploaderCount
  newCount
  lastUploadAt
  folder {
    id
    name
    parentId
  }
}`) as unknown as TypedDocumentString<MyFileRequestsQuery, MyFileRequestsQueryVariables>;
export const CreateFileRequestDocument = new TypedDocumentString(`
    mutation CreateFileRequest($folderId: ID!, $input: FileRequestInput!, $password: String) {
  createFileRequest(folderId: $folderId, input: $input, password: $password) {
    ...FileRequestFields
  }
}
    fragment FileRequestFields on FileRequest {
  id
  token
  title
  note
  hasPassword
  requireName
  groupByUploader
  maxFileSize
  maxTotalBytes
  maxFiles
  expiresAt
  createdAt
  fileCount
  totalBytes
  uploaderCount
  newCount
  lastUploadAt
  folder {
    id
    name
    parentId
  }
}`) as unknown as TypedDocumentString<CreateFileRequestMutation, CreateFileRequestMutationVariables>;
export const UpdateFileRequestDocument = new TypedDocumentString(`
    mutation UpdateFileRequest($id: ID!, $input: FileRequestInput!) {
  updateFileRequest(id: $id, input: $input) {
    ...FileRequestFields
  }
}
    fragment FileRequestFields on FileRequest {
  id
  token
  title
  note
  hasPassword
  requireName
  groupByUploader
  maxFileSize
  maxTotalBytes
  maxFiles
  expiresAt
  createdAt
  fileCount
  totalBytes
  uploaderCount
  newCount
  lastUploadAt
  folder {
    id
    name
    parentId
  }
}`) as unknown as TypedDocumentString<UpdateFileRequestMutation, UpdateFileRequestMutationVariables>;
export const RevokeFileRequestDocument = new TypedDocumentString(`
    mutation RevokeFileRequest($id: ID!) {
  revokeFileRequest(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeFileRequestMutation, RevokeFileRequestMutationVariables>;
export const MarkFileRequestSeenDocument = new TypedDocumentString(`
    mutation MarkFileRequestSeen($id: ID!) {
  markFileRequestSeen(id: $id)
}
    `) as unknown as TypedDocumentString<MarkFileRequestSeenMutation, MarkFileRequestSeenMutationVariables>;
export const FileRequestUploadsDocument = new TypedDocumentString(`
    query FileRequestUploads($id: ID!, $cursor: String) {
  fileRequestUploads(id: $id, cursor: $cursor) {
    items {
      id
      fileName
      size
      uploaderName
      ip
      userAgent
      createdAt
      node {
        id
        parentId
        name
      }
    }
    nextCursor
  }
}
    `) as unknown as TypedDocumentString<FileRequestUploadsQuery, FileRequestUploadsQueryVariables>;
export const FileRequestInfoDocument = new TypedDocumentString(`
    query FileRequestInfo($token: String!) {
  fileRequestInfo(token: $token) {
    title
    note
    needPassword
    needName
    maxFileSize
    expiresAt
    closed
  }
}
    `) as unknown as TypedDocumentString<FileRequestInfoQuery, FileRequestInfoQueryVariables>;
export const OpenFileRequestDocument = new TypedDocumentString(`
    mutation OpenFileRequest($token: String!, $password: String, $uploaderName: String) {
  openFileRequest(token: $token, password: $password, uploaderName: $uploaderName) {
    accessToken
  }
}
    `) as unknown as TypedDocumentString<OpenFileRequestMutation, OpenFileRequestMutationVariables>;
export const CollectInitUploadDocument = new TypedDocumentString(`
    mutation CollectInitUpload($name: String!, $size: Int64!) {
  collectInitUpload(name: $name, size: $size) {
    ...CollectSessionFields
  }
}
    fragment CollectSessionFields on UploadSession {
  id
  partSize
  partUrls {
    partNumber
    url
  }
  uploadedParts
  expiresAt
  status
}`) as unknown as TypedDocumentString<CollectInitUploadMutation, CollectInitUploadMutationVariables>;
export const CollectUploadSessionDocument = new TypedDocumentString(`
    query CollectUploadSession($id: ID!) {
  collectUploadSession(id: $id) {
    ...CollectSessionFields
  }
}
    fragment CollectSessionFields on UploadSession {
  id
  partSize
  partUrls {
    partNumber
    url
  }
  uploadedParts
  expiresAt
  status
}`) as unknown as TypedDocumentString<CollectUploadSessionQuery, CollectUploadSessionQueryVariables>;
export const CollectCompleteUploadDocument = new TypedDocumentString(`
    mutation CollectCompleteUpload($sessionId: ID!, $etags: [PartEtag!]!) {
  collectCompleteUpload(sessionId: $sessionId, etags: $etags) {
    name
    size
    createdAt
  }
}
    `) as unknown as TypedDocumentString<CollectCompleteUploadMutation, CollectCompleteUploadMutationVariables>;
export const CollectAbortUploadDocument = new TypedDocumentString(`
    mutation CollectAbortUpload($sessionId: ID!) {
  collectAbortUpload(sessionId: $sessionId)
}
    `) as unknown as TypedDocumentString<CollectAbortUploadMutation, CollectAbortUploadMutationVariables>;
export const CollectMyUploadsDocument = new TypedDocumentString(`
    query CollectMyUploads {
  collectMyUploads {
    name
    size
    createdAt
  }
}
    `) as unknown as TypedDocumentString<CollectMyUploadsQuery, CollectMyUploadsQueryVariables>;
export const FeedsDocument = new TypedDocumentString(`
    query Feeds {
  feeds {
    ...FeedFields
  }
}
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}`) as unknown as TypedDocumentString<FeedsQuery, FeedsQueryVariables>;
export const FeedDocument = new TypedDocumentString(`
    query Feed($id: ID!) {
  feed(id: $id) {
    ...FeedFields
  }
}
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}`) as unknown as TypedDocumentString<FeedQuery, FeedQueryVariables>;
export const FeedItemsDocument = new TypedDocumentString(`
    query FeedItems($feedId: ID!, $cursor: String) {
  feedItems(feedId: $feedId, cursor: $cursor) {
    items {
      ...FeedItemFields
    }
    nextCursor
  }
}
    fragment FeedItemFields on FeedItem {
  id
  title
  url
  enclosureUrl
  enclosureType
  enclosureSize
  publishedAt
  state
  error
  node {
    id
    parentId
    name
    kind
  }
  jobId
  createdAt
}`) as unknown as TypedDocumentString<FeedItemsQuery, FeedItemsQueryVariables>;
export const PreviewFeedDocument = new TypedDocumentString(`
    query PreviewFeed($url: String!, $assetPattern: String, $prerelease: Boolean) {
  previewFeed(url: $url, assetPattern: $assetPattern, prerelease: $prerelease) {
    kind
    url
    title
    siteUrl
    podcast
    suggestedMode
    items {
      title
      url
      enclosureUrl
      enclosureSize
      publishedAt
    }
  }
}
    `) as unknown as TypedDocumentString<PreviewFeedQuery, PreviewFeedQueryVariables>;
export const ExportOpmlDocument = new TypedDocumentString(`
    query ExportOpml {
  exportOpml
}
    `) as unknown as TypedDocumentString<ExportOpmlQuery, ExportOpmlQueryVariables>;
export const CreateFeedDocument = new TypedDocumentString(`
    mutation CreateFeed($input: FeedInput!) {
  createFeed(input: $input) {
    ...FeedFields
  }
}
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}`) as unknown as TypedDocumentString<CreateFeedMutation, CreateFeedMutationVariables>;
export const UpdateFeedDocument = new TypedDocumentString(`
    mutation UpdateFeed($id: ID!, $input: FeedInput!) {
  updateFeed(id: $id, input: $input) {
    ...FeedFields
  }
}
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}`) as unknown as TypedDocumentString<UpdateFeedMutation, UpdateFeedMutationVariables>;
export const DeleteFeedDocument = new TypedDocumentString(`
    mutation DeleteFeed($id: ID!) {
  deleteFeed(id: $id)
}
    `) as unknown as TypedDocumentString<DeleteFeedMutation, DeleteFeedMutationVariables>;
export const RefreshFeedDocument = new TypedDocumentString(`
    mutation RefreshFeed($id: ID!) {
  refreshFeed(id: $id) {
    ...FeedFields
  }
}
    fragment FeedFields on Feed {
  id
  kind
  url
  title
  siteUrl
  folder {
    id
    parentId
    name
  }
  folderPath
  mode
  includeWords
  excludeWords
  assetPattern
  prerelease
  keepLast
  intervalMinutes
  enabled
  lastCheckedAt
  lastOkAt
  lastError
  failCount
  nextCheckAt
  createdAt
  itemCount
  newCount
  latestAt
}`) as unknown as TypedDocumentString<RefreshFeedMutation, RefreshFeedMutationVariables>;
export const FetchFeedItemDocument = new TypedDocumentString(`
    mutation FetchFeedItem($itemId: ID!, $mode: FeedMode!) {
  fetchFeedItem(itemId: $itemId, mode: $mode) {
    ...FeedItemFields
  }
}
    fragment FeedItemFields on FeedItem {
  id
  title
  url
  enclosureUrl
  enclosureType
  enclosureSize
  publishedAt
  state
  error
  node {
    id
    parentId
    name
    kind
  }
  jobId
  createdAt
}`) as unknown as TypedDocumentString<FetchFeedItemMutation, FetchFeedItemMutationVariables>;
export const ImportOpmlDocument = new TypedDocumentString(`
    mutation ImportOpml($opml: String!) {
  importOpml(opml: $opml) {
    added
    existed
    failed {
      url
      reason
    }
  }
}
    `) as unknown as TypedDocumentString<ImportOpmlMutation, ImportOpmlMutationVariables>;
export const MyJobsDocument = new TypedDocumentString(`
    query MyJobs($active: Boolean, $cursor: String) {
  myJobs(active: $active, cursor: $cursor) {
    items {
      ...JobFields
    }
    nextCursor
  }
}
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<MyJobsQuery, MyJobsQueryVariables>;
export const JobDocument = new TypedDocumentString(`
    query Job($id: ID!) {
  job(id: $id) {
    ...JobFields
  }
}
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<JobQuery, JobQueryVariables>;
export const FetchEnabledDocument = new TypedDocumentString(`
    query FetchEnabled {
  fetchEnabled
}
    `) as unknown as TypedDocumentString<FetchEnabledQuery, FetchEnabledQueryVariables>;
export const FetchUrlsDocument = new TypedDocumentString(`
    mutation FetchUrls($urls: [String!]!, $parentId: ID) {
  fetchUrls(urls: $urls, parentId: $parentId) {
    ...JobFields
  }
}
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<FetchUrlsMutation, FetchUrlsMutationVariables>;
export const CancelJobDocument = new TypedDocumentString(`
    mutation CancelJob($id: ID!) {
  cancelJob(id: $id) {
    ...JobFields
  }
}
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<CancelJobMutation, CancelJobMutationVariables>;
export const RetryJobDocument = new TypedDocumentString(`
    mutation RetryJob($id: ID!) {
  retryJob(id: $id) {
    ...JobFields
  }
}
    fragment JobFields on Job {
  id
  kind
  status
  title
  url
  parentId
  bytesDone
  bytesTotal
  filesDone
  filesTotal
  filesSkipped
  errorCode
  errorMessage
  attempts
  source
  createdAt
  startedAt
  finishedAt
  result {
    id
    name
    kind
    parentId
  }
}`) as unknown as TypedDocumentString<RetryJobMutation, RetryJobMutationVariables>;
export const ClearFinishedJobsDocument = new TypedDocumentString(`
    mutation ClearFinishedJobs {
  clearFinishedJobs
}
    `) as unknown as TypedDocumentString<ClearFinishedJobsMutation, ClearFinishedJobsMutationVariables>;
export const LinksEnabledDocument = new TypedDocumentString(`
    query LinksEnabled {
  linksEnabled
}
    `) as unknown as TypedDocumentString<LinksEnabledQuery, LinksEnabledQueryVariables>;
export const MyLinksDocument = new TypedDocumentString(`
    query MyLinks($cursor: String) {
  myLinks(cursor: $cursor) {
    items {
      ...DirectLinkFields
    }
    nextCursor
    total
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}`) as unknown as TypedDocumentString<MyLinksQuery, MyLinksQueryVariables>;
export const LinkDocument = new TypedDocumentString(`
    query Link($id: ID!) {
  link(id: $id) {
    ...DirectLinkFields
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}`) as unknown as TypedDocumentString<LinkQuery, LinkQueryVariables>;
export const LinkStatsDocument = new TypedDocumentString(`
    query LinkStats($id: ID!, $hours: Int) {
  linkStats(id: $id, hours: $hours) {
    hour
    hits
    bytes
    blocked
    ips
  }
}
    `) as unknown as TypedDocumentString<LinkStatsQuery, LinkStatsQueryVariables>;
export const LinkReferersDocument = new TypedDocumentString(`
    query LinkReferers($id: ID!, $days: Int) {
  linkReferers(id: $id, days: $days) {
    host
    hits
    bytes
  }
}
    `) as unknown as TypedDocumentString<LinkReferersQuery, LinkReferersQueryVariables>;
export const LinkHitsDocument = new TypedDocumentString(`
    query LinkHits($id: ID!, $cursor: String) {
  linkHits(id: $id, cursor: $cursor) {
    items {
      at
      ip
      userAgent
      referer
      status
      bytes
    }
    nextCursor
  }
}
    `) as unknown as TypedDocumentString<LinkHitsQuery, LinkHitsQueryVariables>;
export const LinkPrefsDocument = new TypedDocumentString(`
    query LinkPrefs {
  linkPrefs {
    ...LinkPrefsFields
  }
}
    fragment LinkPrefsFields on LinkPrefs {
  refererHosts
  allowNoReferer
  imageFolder
  stripGps
  dailyBytesCapDefault
  monthlyBytesCap
  monthlyBytesUsed
}`) as unknown as TypedDocumentString<LinkPrefsQuery, LinkPrefsQueryVariables>;
export const CreateLinksDocument = new TypedDocumentString(`
    mutation CreateLinks($nodeIds: [ID!]!) {
  createLinks(nodeIds: $nodeIds) {
    ...DirectLinkFields
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}`) as unknown as TypedDocumentString<CreateLinksMutation, CreateLinksMutationVariables>;
export const UpdateLinkDocument = new TypedDocumentString(`
    mutation UpdateLink($id: ID!, $input: DirectLinkInput!) {
  updateLink(id: $id, input: $input) {
    ...DirectLinkFields
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}`) as unknown as TypedDocumentString<UpdateLinkMutation, UpdateLinkMutationVariables>;
export const PauseLinkDocument = new TypedDocumentString(`
    mutation PauseLink($id: ID!, $paused: Boolean!) {
  pauseLink(id: $id, paused: $paused) {
    ...DirectLinkFields
  }
}
    fragment LinkUsageFields on LinkUsage {
  hits
  bytes
  blocked
}
fragment DirectLinkFields on DirectLink {
  id
  token
  path
  url
  nodeId
  name
  mime
  size
  status
  pauseReason
  source
  createdAt
  expiresAt
  refererHosts
  allowNoReferer
  dailyBytesCap
  autoPause
  hasGps
  hitsTotal
  bytesTotal
  lastHitAt
  today {
    ...LinkUsageFields
  }
  last30Days {
    ...LinkUsageFields
  }
}`) as unknown as TypedDocumentString<PauseLinkMutation, PauseLinkMutationVariables>;
export const RevokeLinkDocument = new TypedDocumentString(`
    mutation RevokeLink($id: ID!) {
  revokeLink(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeLinkMutation, RevokeLinkMutationVariables>;
export const SetLinkPrefsDocument = new TypedDocumentString(`
    mutation SetLinkPrefs($input: LinkPrefsInput!) {
  setLinkPrefs(input: $input) {
    ...LinkPrefsFields
  }
}
    fragment LinkPrefsFields on LinkPrefs {
  refererHosts
  allowNoReferer
  imageFolder
  stripGps
  dailyBytesCapDefault
  monthlyBytesCap
  monthlyBytesUsed
}`) as unknown as TypedDocumentString<SetLinkPrefsMutation, SetLinkPrefsMutationVariables>;
export const AdminLinkStatsDocument = new TypedDocumentString(`
    query AdminLinkStats {
  adminLinkStats {
    monthHits
    monthBytes
    monthlyBytesCap
    top {
      id
      owner
      name
      path
      status
      monthHits
      monthBytes
    }
  }
}
    `) as unknown as TypedDocumentString<AdminLinkStatsQuery, AdminLinkStatsQueryVariables>;
export const AdminPauseLinkDocument = new TypedDocumentString(`
    mutation AdminPauseLink($id: ID!, $paused: Boolean!) {
  adminPauseLink(id: $id, paused: $paused)
}
    `) as unknown as TypedDocumentString<AdminPauseLinkMutation, AdminPauseLinkMutationVariables>;
export const AdminRevokeLinkDocument = new TypedDocumentString(`
    mutation AdminRevokeLink($id: ID!) {
  adminRevokeLink(id: $id)
}
    `) as unknown as TypedDocumentString<AdminRevokeLinkMutation, AdminRevokeLinkMutationVariables>;
export const NodeThumbDocument = new TypedDocumentString(`
    query NodeThumb($id: ID!) {
  node(id: $id) {
    id
    preview {
      kind
      thumbUrl
    }
  }
}
    `) as unknown as TypedDocumentString<NodeThumbQuery, NodeThumbQueryVariables>;
export const McpapiKeysDocument = new TypedDocumentString(`
    query MCPAPIKeys {
  mcpAPIKeys {
    id
    name
    scopes
    createdAt
    lastUsedAt
  }
}
    `) as unknown as TypedDocumentString<McpapiKeysQuery, McpapiKeysQueryVariables>;
export const CreateMcpapiKeyDocument = new TypedDocumentString(`
    mutation CreateMCPAPIKey($name: String!, $scopes: [String!]!) {
  createMCPAPIKey(name: $name, scopes: $scopes) {
    token
    credential {
      id
      name
      scopes
      createdAt
      lastUsedAt
    }
  }
}
    `) as unknown as TypedDocumentString<CreateMcpapiKeyMutation, CreateMcpapiKeyMutationVariables>;
export const RevokeMcpapiKeyDocument = new TypedDocumentString(`
    mutation RevokeMCPAPIKey($id: ID!) {
  revokeMCPAPIKey(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeMcpapiKeyMutation, RevokeMcpapiKeyMutationVariables>;
export const OAuthGrantsDocument = new TypedDocumentString(`
    query OAuthGrants {
  oauthGrants {
    id
    clientId
    clientName
    scopes
    createdAt
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<OAuthGrantsQuery, OAuthGrantsQueryVariables>;
export const RevokeOAuthGrantDocument = new TypedDocumentString(`
    mutation RevokeOAuthGrant($id: ID!) {
  revokeOAuthGrant(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeOAuthGrantMutation, RevokeOAuthGrantMutationVariables>;
export const OAuthAuthorizationRequestDocument = new TypedDocumentString(`
    query OAuthAuthorizationRequest($input: OAuthAuthorizationInput!) {
  oauthAuthorizationRequest(input: $input) {
    clientName
    scopes
  }
}
    `) as unknown as TypedDocumentString<OAuthAuthorizationRequestQuery, OAuthAuthorizationRequestQueryVariables>;
export const DecideOAuthAuthorizationDocument = new TypedDocumentString(`
    mutation DecideOAuthAuthorization($input: OAuthAuthorizationInput!, $approved: Boolean!) {
  decideOAuthAuthorization(input: $input, approved: $approved) {
    redirectUrl
  }
}
    `) as unknown as TypedDocumentString<DecideOAuthAuthorizationMutation, DecideOAuthAuthorizationMutationVariables>;
export const McpAccessRootsDocument = new TypedDocumentString(`
    query McpAccessRoots {
  mcpAccessRoots {
    credentialId
    credentialType
    rootId
    rootName
  }
}
    `) as unknown as TypedDocumentString<McpAccessRootsQuery, McpAccessRootsQueryVariables>;
export const SetMcpAccessRootDocument = new TypedDocumentString(`
    mutation SetMcpAccessRoot($credentialId: ID!, $credentialType: String!, $rootPath: String) {
  setMCPAccessRoot(
    credentialId: $credentialId
    credentialType: $credentialType
    rootPath: $rootPath
  )
}
    `) as unknown as TypedDocumentString<SetMcpAccessRootMutation, SetMcpAccessRootMutationVariables>;
export const McpAuditDocument = new TypedDocumentString(`
    query McpAudit($beforeId: Int64, $limit: Int) {
  mcpAudit(beforeId: $beforeId, limit: $limit) {
    id
    credentialId
    credentialType
    endpoint
    tool
    status
    errorCode
    createdAt
  }
}
    `) as unknown as TypedDocumentString<McpAuditQuery, McpAuditQueryVariables>;
export const PlaylistDocument = new TypedDocumentString(`
    query Playlist($nodeId: ID!) {
  playlist(nodeId: $nodeId) {
    id
    parentId
    name
    kind
    size
    preview {
      kind
    }
  }
}
    `) as unknown as TypedDocumentString<PlaylistQuery, PlaylistQueryVariables>;
export const RecentPlaysDocument = new TypedDocumentString(`
    query RecentPlays($limit: Int) {
  recentPlays(limit: $limit) {
    node {
      id
      parentId
      name
      kind
      preview {
        kind
        thumbUrl
      }
    }
    progress {
      ...PlayProgressFields
    }
  }
}
    fragment PlayProgressFields on PlayProgress {
  position
  duration
  finished
  updatedAt
}`) as unknown as TypedDocumentString<RecentPlaysQuery, RecentPlaysQueryVariables>;
export const SaveProgressDocument = new TypedDocumentString(`
    mutation SaveProgress($nodeId: ID!, $position: Float!, $duration: Float!) {
  saveProgress(nodeId: $nodeId, position: $position, duration: $duration) {
    ...PlayProgressFields
  }
}
    fragment PlayProgressFields on PlayProgress {
  position
  duration
  finished
  updatedAt
}`) as unknown as TypedDocumentString<SaveProgressMutation, SaveProgressMutationVariables>;
export const ClearProgressDocument = new TypedDocumentString(`
    mutation ClearProgress($nodeId: ID!) {
  clearProgress(nodeId: $nodeId)
}
    `) as unknown as TypedDocumentString<ClearProgressMutation, ClearProgressMutationVariables>;
export const ChildrenDocument = new TypedDocumentString(`
    query Children($parentId: ID, $cursor: String, $order: NodeOrder, $desc: Boolean) {
  children(parentId: $parentId, cursor: $cursor, order: $order, desc: $desc) {
    items {
      id
      parentId
      name
      kind
      size
      subtreeBytes
      subtreeCount
      statsStale
      updatedAt
      starred
      preview {
        kind
        thumbUrl
      }
    }
    nextCursor
    total
  }
}
    `) as unknown as TypedDocumentString<ChildrenQuery, ChildrenQueryVariables>;
export const NodePreviewDocument = new TypedDocumentString(`
    query NodePreview($id: ID!) {
  node(id: $id) {
    id
    parentId
    name
    kind
    size
    preview {
      kind
      thumbUrl
      largeUrl
      contentUrl
      status
      durationSec
      markdown
      subtitles {
        nodeId
        name
        label
        lang
        default
      }
    }
    playProgress {
      position
      duration
      finished
      updatedAt
    }
  }
}
    `) as unknown as TypedDocumentString<NodePreviewQuery, NodePreviewQueryVariables>;
export const RequestPreviewDocument = new TypedDocumentString(`
    mutation RequestPreview($nodeId: ID!) {
  requestPreview(nodeId: $nodeId) {
    kind
    status
    contentUrl
  }
}
    `) as unknown as TypedDocumentString<RequestPreviewMutation, RequestPreviewMutationVariables>;
export const NodeDocument = new TypedDocumentString(`
    query Node($id: ID!) {
  node(id: $id) {
    id
    parentId
    name
    kind
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<NodeQuery, NodeQueryVariables>;
export const NodeDetailDocument = new TypedDocumentString(`
    query NodeDetail($id: ID!) {
  node(id: $id) {
    id
    parentId
    name
    kind
    size
    mime
    sha256
    createdAt
    updatedAt
    starred
    subtreeBytes
    subtreeCount
    statsStale
    preview {
      kind
      thumbUrl
    }
  }
}
    `) as unknown as TypedDocumentString<NodeDetailQuery, NodeDetailQueryVariables>;
export const CreateFolderDocument = new TypedDocumentString(`
    mutation CreateFolder($parentId: ID, $name: String!) {
  createFolder(parentId: $parentId, name: $name) {
    id
    parentId
    name
    kind
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<CreateFolderMutation, CreateFolderMutationVariables>;
export const RenameNodeDocument = new TypedDocumentString(`
    mutation RenameNode($id: ID!, $name: String!) {
  renameNode(id: $id, name: $name) {
    id
    parentId
    name
    kind
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<RenameNodeMutation, RenameNodeMutationVariables>;
export const MoveNodesDocument = new TypedDocumentString(`
    mutation MoveNodes($ids: [ID!]!, $targetParentId: ID) {
  moveNodes(ids: $ids, targetParentId: $targetParentId) {
    id
    parentId
    name
  }
}
    `) as unknown as TypedDocumentString<MoveNodesMutation, MoveNodesMutationVariables>;
export const DeleteNodesDocument = new TypedDocumentString(`
    mutation DeleteNodes($ids: [ID!]!) {
  deleteNodes(ids: $ids)
}
    `) as unknown as TypedDocumentString<DeleteNodesMutation, DeleteNodesMutationVariables>;
export const CopyNodesDocument = new TypedDocumentString(`
    mutation CopyNodes($ids: [ID!]!, $targetParentId: ID) {
  copyNodes(ids: $ids, targetParentId: $targetParentId) {
    id
    parentId
    name
  }
}
    `) as unknown as TypedDocumentString<CopyNodesMutation, CopyNodesMutationVariables>;
export const SearchNodesDocument = new TypedDocumentString(`
    query SearchNodes($q: String!, $cursor: String) {
  searchNodes(q: $q, cursor: $cursor) {
    items {
      id
      parentId
      name
      kind
      size
      updatedAt
      path
      preview {
        kind
        thumbUrl
      }
    }
    nextCursor
    total
  }
}
    `) as unknown as TypedDocumentString<SearchNodesQuery, SearchNodesQueryVariables>;
export const SearchContentDocument = new TypedDocumentString(`
    query SearchContent($q: String!, $cursor: String) {
  searchContent(q: $q, cursor: $cursor) {
    items {
      node {
        id
        parentId
        name
        kind
        size
        mime
        updatedAt
        preview {
          kind
          thumbUrl
        }
      }
      snippet
      highlights {
        start
        length
      }
      page
    }
    nextCursor
  }
}
    `) as unknown as TypedDocumentString<SearchContentQuery, SearchContentQueryVariables>;
export const StarredNodesDocument = new TypedDocumentString(`
    query StarredNodes {
  starredNodes {
    ...QuickNode
  }
}
    fragment QuickNode on Node {
  id
  parentId
  name
  kind
  size
  subtreeBytes
  subtreeCount
  statsStale
  updatedAt
  openedAt
  starred
  path
  preview {
    kind
    thumbUrl
  }
}`) as unknown as TypedDocumentString<StarredNodesQuery, StarredNodesQueryVariables>;
export const RecentFilesDocument = new TypedDocumentString(`
    query RecentFiles {
  recentFiles {
    ...QuickNode
  }
}
    fragment QuickNode on Node {
  id
  parentId
  name
  kind
  size
  subtreeBytes
  subtreeCount
  statsStale
  updatedAt
  openedAt
  starred
  path
  preview {
    kind
    thumbUrl
  }
}`) as unknown as TypedDocumentString<RecentFilesQuery, RecentFilesQueryVariables>;
export const SetStarredDocument = new TypedDocumentString(`
    mutation SetStarred($ids: [ID!]!, $starred: Boolean!) {
  setStarred(ids: $ids, starred: $starred) {
    id
    starred
  }
}
    `) as unknown as TypedDocumentString<SetStarredMutation, SetStarredMutationVariables>;
export const MarkOpenedDocument = new TypedDocumentString(`
    mutation MarkOpened($nodeId: ID!) {
  markOpened(nodeId: $nodeId)
}
    `) as unknown as TypedDocumentString<MarkOpenedMutation, MarkOpenedMutationVariables>;
export const ClearRecentDocument = new TypedDocumentString(`
    mutation ClearRecent($ids: [ID!]) {
  clearRecent(ids: $ids)
}
    `) as unknown as TypedDocumentString<ClearRecentMutation, ClearRecentMutationVariables>;
export const FileVersionsDocument = new TypedDocumentString(`
    query FileVersions($nodeId: ID!) {
  fileVersions(nodeId: $nodeId) {
    items {
      id
      size
      savedAt
      replacedAt
      downloadUrl
    }
    keep
    keepDays
  }
}
    `) as unknown as TypedDocumentString<FileVersionsQuery, FileVersionsQueryVariables>;
export const RestoreFileVersionDocument = new TypedDocumentString(`
    mutation RestoreFileVersion($id: ID!) {
  restoreFileVersion(id: $id) {
    id
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<RestoreFileVersionMutation, RestoreFileVersionMutationVariables>;
export const DeleteFileVersionsDocument = new TypedDocumentString(`
    mutation DeleteFileVersions($ids: [ID!]!) {
  deleteFileVersions(ids: $ids)
}
    `) as unknown as TypedDocumentString<DeleteFileVersionsMutation, DeleteFileVersionsMutationVariables>;
export const PurgeFileVersionsDocument = new TypedDocumentString(`
    mutation PurgeFileVersions($nodeId: ID) {
  purgeFileVersions(nodeId: $nodeId)
}
    `) as unknown as TypedDocumentString<PurgeFileVersionsMutation, PurgeFileVersionsMutationVariables>;
export const TextFileDocument = new TypedDocumentString(`
    query TextFile($nodeId: ID!) {
  textFile(nodeId: $nodeId) {
    content
    sha256
    size
  }
}
    `) as unknown as TypedDocumentString<TextFileQuery, TextFileQueryVariables>;
export const SaveTextFileDocument = new TypedDocumentString(`
    mutation SaveTextFile($nodeId: ID!, $content: String!, $baseSha256: String) {
  saveTextFile(nodeId: $nodeId, content: $content, baseSha256: $baseSha256) {
    id
    size
    sha256
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<SaveTextFileMutation, SaveTextFileMutationVariables>;
export const CreateTextFileDocument = new TypedDocumentString(`
    mutation CreateTextFile($parentId: ID, $name: String!, $content: String) {
  createTextFile(parentId: $parentId, name: $name, content: $content) {
    id
    name
  }
}
    `) as unknown as TypedDocumentString<CreateTextFileMutation, CreateTextFileMutationVariables>;
export const NotificationsDocument = new TypedDocumentString(`
    query Notifications($cursor: String, $unreadOnly: Boolean) {
  notifications(cursor: $cursor, unreadOnly: $unreadOnly) {
    items {
      id
      kind
      level
      title
      body
      count
      target {
        kind
        id
        parentId
      }
      createdAt
      updatedAt
      read
    }
    nextCursor
  }
}
    `) as unknown as TypedDocumentString<NotificationsQuery, NotificationsQueryVariables>;
export const MarkNotificationsReadDocument = new TypedDocumentString(`
    mutation MarkNotificationsRead($ids: [ID!]) {
  markNotificationsRead(ids: $ids)
}
    `) as unknown as TypedDocumentString<MarkNotificationsReadMutation, MarkNotificationsReadMutationVariables>;
export const ClearNotificationsDocument = new TypedDocumentString(`
    mutation ClearNotifications {
  clearNotifications
}
    `) as unknown as TypedDocumentString<ClearNotificationsMutation, ClearNotificationsMutationVariables>;
export const NotifyChannelsDocument = new TypedDocumentString(`
    query NotifyChannels {
  notifyChannels {
    ...NotifyChannelFields
  }
}
    fragment NotifyChannelFields on NotifyChannel {
  id
  kind
  name
  enabled
  events
  url
  topic
  hasSecret
  secretHint
  lastOkAt
  lastError
  lastErrorAt
  createdAt
}`) as unknown as TypedDocumentString<NotifyChannelsQuery, NotifyChannelsQueryVariables>;
export const NotifyEventKindsDocument = new TypedDocumentString(`
    query NotifyEventKinds {
  notifyEventKinds {
    kind
    label
    defaultPush
    adminOnly
  }
}
    `) as unknown as TypedDocumentString<NotifyEventKindsQuery, NotifyEventKindsQueryVariables>;
export const CreateNotifyChannelDocument = new TypedDocumentString(`
    mutation CreateNotifyChannel($input: NotifyChannelInput!) {
  createNotifyChannel(input: $input) {
    ...NotifyChannelFields
  }
}
    fragment NotifyChannelFields on NotifyChannel {
  id
  kind
  name
  enabled
  events
  url
  topic
  hasSecret
  secretHint
  lastOkAt
  lastError
  lastErrorAt
  createdAt
}`) as unknown as TypedDocumentString<CreateNotifyChannelMutation, CreateNotifyChannelMutationVariables>;
export const UpdateNotifyChannelDocument = new TypedDocumentString(`
    mutation UpdateNotifyChannel($id: ID!, $input: NotifyChannelInput!) {
  updateNotifyChannel(id: $id, input: $input) {
    ...NotifyChannelFields
  }
}
    fragment NotifyChannelFields on NotifyChannel {
  id
  kind
  name
  enabled
  events
  url
  topic
  hasSecret
  secretHint
  lastOkAt
  lastError
  lastErrorAt
  createdAt
}`) as unknown as TypedDocumentString<UpdateNotifyChannelMutation, UpdateNotifyChannelMutationVariables>;
export const DeleteNotifyChannelDocument = new TypedDocumentString(`
    mutation DeleteNotifyChannel($id: ID!) {
  deleteNotifyChannel(id: $id)
}
    `) as unknown as TypedDocumentString<DeleteNotifyChannelMutation, DeleteNotifyChannelMutationVariables>;
export const TestNotifyChannelDocument = new TypedDocumentString(`
    mutation TestNotifyChannel($id: ID!) {
  testNotifyChannel(id: $id)
}
    `) as unknown as TypedDocumentString<TestNotifyChannelMutation, TestNotifyChannelMutationVariables>;
export const SessionsDocument = new TypedDocumentString(`
    query Sessions {
  sessions {
    familyId
    userAgent
    ip
    createdAt
    lastSeenAt
    revokedAt
    active
    current
  }
}
    `) as unknown as TypedDocumentString<SessionsQuery, SessionsQueryVariables>;
export const RevokeSessionDocument = new TypedDocumentString(`
    mutation RevokeSession($familyId: ID!) {
  revokeSession(familyId: $familyId)
}
    `) as unknown as TypedDocumentString<RevokeSessionMutation, RevokeSessionMutationVariables>;
export const CreateShareDocument = new TypedDocumentString(`
    mutation CreateShare($nodeId: ID!, $password: String, $expiresAt: Time) {
  createShare(nodeId: $nodeId, password: $password, expiresAt: $expiresAt) {
    id
    token
    hasPassword
    expiresAt
    createdAt
    node {
      id
      name
      kind
    }
  }
}
    `) as unknown as TypedDocumentString<CreateShareMutation, CreateShareMutationVariables>;
export const RevokeShareDocument = new TypedDocumentString(`
    mutation RevokeShare($id: ID!) {
  revokeShare(id: $id)
}
    `) as unknown as TypedDocumentString<RevokeShareMutation, RevokeShareMutationVariables>;
export const MySharesDocument = new TypedDocumentString(`
    query MyShares {
  myShares {
    id
    token
    hasPassword
    expiresAt
    createdAt
    visitStats {
      verifyCount
      downloadCount
      lastVisitAt
      lastVisitKind
    }
    node {
      id
      name
      kind
    }
  }
}
    `) as unknown as TypedDocumentString<MySharesQuery, MySharesQueryVariables>;
export const ShareVisitsDocument = new TypedDocumentString(`
    query ShareVisits($shareId: ID!, $limit: Int) {
  shareVisits(shareId: $shareId, limit: $limit) {
    kind
    ip
    userAgent
    createdAt
  }
}
    `) as unknown as TypedDocumentString<ShareVisitsQuery, ShareVisitsQueryVariables>;
export const ShareInfoDocument = new TypedDocumentString(`
    query ShareInfo($token: String!) {
  shareInfo(token: $token) {
    token
    name
    kind
    needPassword
    expired
    expiresAt
    size
    fileCount
  }
}
    `) as unknown as TypedDocumentString<ShareInfoQuery, ShareInfoQueryVariables>;
export const VerifySharePasswordDocument = new TypedDocumentString(`
    mutation VerifySharePassword($token: String!, $password: String!) {
  verifySharePassword(token: $token, password: $password) {
    accessToken
  }
}
    `) as unknown as TypedDocumentString<VerifySharePasswordMutation, VerifySharePasswordMutationVariables>;
export const ShareRootDocument = new TypedDocumentString(`
    query ShareRoot {
  shareRoot {
    id
    name
    kind
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<ShareRootQuery, ShareRootQueryVariables>;
export const SpaceOverviewDocument = new TypedDocumentString(`
    query SpaceOverview {
  spaceOverview {
    usedBytes
    quotaBytes
    trashFiles
    trashBytes
    byType {
      category
      files
      bytes
    }
    duplicateGroups
    duplicateBytes
    versionFiles
    versionBytes
  }
}
    `) as unknown as TypedDocumentString<SpaceOverviewQuery, SpaceOverviewQueryVariables>;
export const SpaceFoldersDocument = new TypedDocumentString(`
    query SpaceFolders($parentId: ID) {
  spaceFolders(parentId: $parentId) {
    path
    folders {
      id
      parentId
      name
      kind
      subtreeBytes
      subtreeCount
      statsStale
      updatedAt
    }
    looseFiles
    looseBytes
  }
}
    `) as unknown as TypedDocumentString<SpaceFoldersQuery, SpaceFoldersQueryVariables>;
export const SpaceLargeFilesDocument = new TypedDocumentString(`
    query SpaceLargeFiles($category: FileCategory, $limit: Int) {
  spaceLargeFiles(category: $category, limit: $limit) {
    ...SpaceFileFields
  }
}
    fragment SpaceFileFields on SpaceFile {
  node {
    id
    parentId
    name
    kind
    size
    mime
    updatedAt
    preview {
      kind
      thumbUrl
    }
  }
  path
  category
}`) as unknown as TypedDocumentString<SpaceLargeFilesQuery, SpaceLargeFilesQueryVariables>;
export const SpaceStaleFilesDocument = new TypedDocumentString(`
    query SpaceStaleFiles($days: Int, $minSize: Int64, $limit: Int) {
  spaceStaleFiles(days: $days, minSize: $minSize, limit: $limit) {
    ...SpaceFileFields
  }
}
    fragment SpaceFileFields on SpaceFile {
  node {
    id
    parentId
    name
    kind
    size
    mime
    updatedAt
    preview {
      kind
      thumbUrl
    }
  }
  path
  category
}`) as unknown as TypedDocumentString<SpaceStaleFilesQuery, SpaceStaleFilesQueryVariables>;
export const SpaceDuplicatesDocument = new TypedDocumentString(`
    query SpaceDuplicates($offset: Int, $limit: Int) {
  spaceDuplicates(offset: $offset, limit: $limit) {
    items {
      sha256
      size
      copies
      files {
        ...SpaceFileFields
      }
    }
    nextOffset
  }
}
    fragment SpaceFileFields on SpaceFile {
  node {
    id
    parentId
    name
    kind
    size
    mime
    updatedAt
    preview {
      kind
      thumbUrl
    }
  }
  path
  category
}`) as unknown as TypedDocumentString<SpaceDuplicatesQuery, SpaceDuplicatesQueryVariables>;
export const SpaceEmptyFoldersDocument = new TypedDocumentString(`
    query SpaceEmptyFolders($limit: Int) {
  spaceEmptyFolders(limit: $limit) {
    ...SpaceFileFields
  }
}
    fragment SpaceFileFields on SpaceFile {
  node {
    id
    parentId
    name
    kind
    size
    mime
    updatedAt
    preview {
      kind
      thumbUrl
    }
  }
  path
  category
}`) as unknown as TypedDocumentString<SpaceEmptyFoldersQuery, SpaceEmptyFoldersQueryVariables>;
export const SpaceIdleDocument = new TypedDocumentString(`
    query SpaceIdle {
  spaceIdle {
    shares {
      id
      token
      nodeId
      name
      kind
      expiresAt
      createdAt
      lastVisitAt
      expired
    }
    links {
      id
      nodeId
      name
      expiresAt
      createdAt
      lastHitAt
      hits
      expired
    }
    requests {
      id
      title
      folderId
      expiresAt
      createdAt
    }
  }
}
    `) as unknown as TypedDocumentString<SpaceIdleQuery, SpaceIdleQueryVariables>;
export const AdminStorageDocument = new TypedDocumentString(`
    query AdminStorage {
  adminStorage {
    logicalBytes
    physicalBytes
    blobs
    pendingGcBytes
    derivativeBytes
    derivatives
    textIndexBytes
    activityBytes
  }
}
    `) as unknown as TypedDocumentString<AdminStorageQuery, AdminStorageQueryVariables>;
export const TrashDocument = new TypedDocumentString(`
    query Trash($cursor: String) {
  trash(cursor: $cursor) {
    items {
      id
      parentId
      name
      kind
      size
      updatedAt
      deletedAt
      purgeAt
      preview {
        kind
        thumbUrl
      }
    }
    nextCursor
    total
  }
}
    `) as unknown as TypedDocumentString<TrashQuery, TrashQueryVariables>;
export const RestoreNodesDocument = new TypedDocumentString(`
    mutation RestoreNodes($ids: [ID!]!) {
  restoreNodes(ids: $ids) {
    id
    parentId
    name
  }
}
    `) as unknown as TypedDocumentString<RestoreNodesMutation, RestoreNodesMutationVariables>;
export const PurgeNodesDocument = new TypedDocumentString(`
    mutation PurgeNodes($ids: [ID!]!) {
  purgeNodes(ids: $ids)
}
    `) as unknown as TypedDocumentString<PurgeNodesMutation, PurgeNodesMutationVariables>;
export const PurgeTrashDocument = new TypedDocumentString(`
    mutation PurgeTrash {
  purgeTrash
}
    `) as unknown as TypedDocumentString<PurgeTrashMutation, PurgeTrashMutationVariables>;
export const InitUploadDocument = new TypedDocumentString(`
    mutation InitUpload($parentId: ID, $name: String!, $sha256: String!, $size: Int64!, $replace: Boolean) {
  initUpload(
    parentId: $parentId
    name: $name
    sha256: $sha256
    size: $size
    replace: $replace
  ) {
    instant
    node {
      id
      parentId
      name
      kind
      size
      updatedAt
    }
    session {
      id
      partSize
      partUrls {
        partNumber
        url
      }
      uploadedParts
      expiresAt
      status
    }
  }
}
    `) as unknown as TypedDocumentString<InitUploadMutation, InitUploadMutationVariables>;
export const CompleteUploadDocument = new TypedDocumentString(`
    mutation CompleteUpload($sessionId: ID!, $etags: [PartEtag!]!) {
  completeUpload(sessionId: $sessionId, etags: $etags) {
    id
    parentId
    name
    kind
    size
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<CompleteUploadMutation, CompleteUploadMutationVariables>;
export const AbortUploadDocument = new TypedDocumentString(`
    mutation AbortUpload($sessionId: ID!) {
  abortUpload(sessionId: $sessionId)
}
    `) as unknown as TypedDocumentString<AbortUploadMutation, AbortUploadMutationVariables>;
export const UploadSessionDocument = new TypedDocumentString(`
    query UploadSession($id: ID!) {
  uploadSession(id: $id) {
    id
    partSize
    partUrls {
      partNumber
      url
    }
    uploadedParts
    expiresAt
    status
  }
}
    `) as unknown as TypedDocumentString<UploadSessionQuery, UploadSessionQueryVariables>;
export const NodeDownloadUrlDocument = new TypedDocumentString(`
    query NodeDownloadUrl($id: ID!) {
  node(id: $id) {
    id
    downloadUrl
  }
}
    `) as unknown as TypedDocumentString<NodeDownloadUrlQuery, NodeDownloadUrlQueryVariables>;