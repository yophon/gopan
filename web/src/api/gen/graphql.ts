/* eslint-disable */
import type { TypedDocumentNode as DocumentNode } from '@graphql-typed-document-node/core';
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
  decideOAuthAuthorization: OAuthAuthorizationResult;
  deleteChatMessage: Scalars['Boolean']['output'];
  deleteFeed: Scalars['Boolean']['output'];
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
  moveNodes: Array<Node>;
  openFileRequest: CollectAuth;
  pauseLink: DirectLink;
  purgeNodes: Scalars['Boolean']['output'];
  purgeTrash: Scalars['Boolean']['output'];
  refresh: AuthPayload;
  refreshFeed: Feed;
  register: AuthPayload;
  renameNode: Node;
  requestPreview: PreviewInfo;
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
  sendChatMessage: ChatMessage;
  setClipPrefs: ClipPrefs;
  setLinkPrefs: LinkPrefs;
  setMCPAccessRoot: Scalars['Boolean']['output'];
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
  parentId?: Maybe<Scalars['ID']['output']>;
  playProgress?: Maybe<PlayProgress>;
  preview: PreviewInfo;
  purgeAt?: Maybe<Scalars['Time']['output']>;
  sha256?: Maybe<Scalars['String']['output']>;
  size?: Maybe<Scalars['Int64']['output']>;
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
  kind: NodeKind;
  name: Scalars['String']['output'];
  needPassword: Scalars['Boolean']['output'];
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

export type ClipFieldsFragment = { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null };

export type ClipsQueryVariables = Exact<{
  unreadOnly?: InputMaybe<Scalars['Boolean']['input']>;
  site?: InputMaybe<Scalars['String']['input']>;
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type ClipsQuery = { __typename?: 'Query', clips: { __typename?: 'ClipPage', nextCursor?: string | null, items: Array<{ __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null }> } };

export type ClipSitesQueryVariables = Exact<{ [key: string]: never; }>;


export type ClipSitesQuery = { __typename?: 'Query', clipSites: Array<{ __typename?: 'ClipSite', site: string, total: number }> };

export type ClipQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type ClipQuery = { __typename?: 'Query', clip: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } };

export type NodeClipQueryVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type NodeClipQuery = { __typename?: 'Query', nodeClip?: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } | null };

export type ClipPrefsQueryVariables = Exact<{ [key: string]: never; }>;


export type ClipPrefsQuery = { __typename?: 'Query', clipPrefs: { __typename?: 'ClipPrefs', folder: string, byMonth: boolean } };

export type ClipUrlMutationVariables = Exact<{
  url: Scalars['String']['input'];
  html?: InputMaybe<Scalars['String']['input']>;
  force?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type ClipUrlMutation = { __typename?: 'Mutation', clipUrl: { __typename?: 'ClipResult', job?: { __typename?: 'Job', id: string, kind: JobKind, status: JobStatus, title: string, url?: string | null, parentId?: string | null, bytesDone: number, bytesTotal?: number | null, filesDone: number, filesTotal?: number | null, filesSkipped: number, errorCode?: string | null, errorMessage?: string | null, attempts: number, source: string, createdAt: string, startedAt?: string | null, finishedAt?: string | null, result?: { __typename?: 'Node', id: string, name: string, kind: NodeKind, parentId?: string | null } | null } | null, existing?: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } | null } };

export type MarkClipReadMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  read?: InputMaybe<Scalars['Boolean']['input']>;
}>;


export type MarkClipReadMutation = { __typename?: 'Mutation', markClipRead: { __typename?: 'Clip', id: string, url: string, title: string, site: string, byline: string, excerpt: string, publishedAt?: string | null, words: number, images: number, readAt?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null } };

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

export type FeedFieldsFragment = { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null };

export type FeedItemFieldsFragment = { __typename?: 'FeedItem', id: string, title: string, url: string, enclosureUrl: string, enclosureType: string, enclosureSize?: number | null, publishedAt?: string | null, state: FeedItemState, error?: string | null, jobId?: string | null, createdAt: string, node?: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind } | null };

export type FeedsQueryVariables = Exact<{ [key: string]: never; }>;


export type FeedsQuery = { __typename?: 'Query', feeds: Array<{ __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null }> };

export type FeedQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type FeedQuery = { __typename?: 'Query', feed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

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


export type CreateFeedMutation = { __typename?: 'Mutation', createFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type UpdateFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
  input: FeedInput;
}>;


export type UpdateFeedMutation = { __typename?: 'Mutation', updateFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

export type DeleteFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type DeleteFeedMutation = { __typename?: 'Mutation', deleteFeed: boolean };

export type RefreshFeedMutationVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type RefreshFeedMutation = { __typename?: 'Mutation', refreshFeed: { __typename?: 'Feed', id: string, kind: FeedKind, url: string, title: string, siteUrl: string, mode: FeedMode, includeWords: string, excludeWords: string, assetPattern: string, prerelease: boolean, keepLast: number, intervalMinutes: number, enabled: boolean, lastCheckedAt?: string | null, lastOkAt?: string | null, lastError?: string | null, failCount: number, nextCheckAt: string, createdAt: string, itemCount: number, newCount: number, latestAt?: string | null, folder?: { __typename?: 'Node', id: string, parentId?: string | null, name: string } | null } };

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


export type ChildrenQuery = { __typename?: 'Query', children: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

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


export type NodeDetailQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, sha256?: string | null, createdAt: string, updatedAt: string, subtreeBytes?: number | null, subtreeCount?: number | null, statsStale?: boolean | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } };

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


export type SearchNodesQuery = { __typename?: 'Query', searchNodes: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

export type SearchContentQueryVariables = Exact<{
  q: Scalars['String']['input'];
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type SearchContentQuery = { __typename?: 'Query', searchContent: { __typename?: 'ContentHitPage', nextCursor?: string | null, items: Array<{ __typename?: 'ContentHit', snippet: string, page?: number | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }, highlights: Array<{ __typename?: 'TextRange', start: number, length: number }> }> } };

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


export type ShareInfoQuery = { __typename?: 'Query', shareInfo: { __typename?: 'ShareInfo', token: string, name: string, kind: NodeKind, needPassword: boolean, expired: boolean } };

export type VerifySharePasswordMutationVariables = Exact<{
  token: Scalars['String']['input'];
  password: Scalars['String']['input'];
}>;


export type VerifySharePasswordMutation = { __typename?: 'Mutation', verifySharePassword: { __typename?: 'ShareAuth', accessToken: string } };

export type ShareRootQueryVariables = Exact<{ [key: string]: never; }>;


export type ShareRootQuery = { __typename?: 'Query', shareRoot: { __typename?: 'Node', id: string, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

export type SpaceFileFieldsFragment = { __typename?: 'SpaceFile', path: string, category?: FileCategory | null, node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, mime?: string | null, updatedAt: string, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } } };

export type SpaceOverviewQueryVariables = Exact<{ [key: string]: never; }>;


export type SpaceOverviewQuery = { __typename?: 'Query', spaceOverview: { __typename?: 'SpaceOverview', usedBytes: number, quotaBytes: number, trashFiles: number, trashBytes: number, duplicateGroups: number, duplicateBytes: number, byType: Array<{ __typename?: 'SpaceCategory', category: FileCategory, files: number, bytes: number }> } };

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


export type SpaceIdleQuery = { __typename?: 'Query', spaceIdle: { __typename?: 'SpaceIdle', shares: Array<{ __typename?: 'IdleShare', id: string, nodeId: string, name: string, kind: NodeKind, expiresAt?: string | null, createdAt: string, lastVisitAt?: string | null, expired: boolean }>, links: Array<{ __typename?: 'IdleLink', id: string, nodeId: string, name: string, expiresAt?: string | null, createdAt: string, lastHitAt?: string | null, hits: number, expired: boolean }>, requests: Array<{ __typename?: 'IdleRequest', id: string, title: string, folderId: string, expiresAt: string, createdAt: string }> } };

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

export const ActivityFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ActivityFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Activity"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"batchId"}},{"kind":"Field","name":{"kind":"Name","value":"action"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nodeKind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentPath"}},{"kind":"Field","name":{"kind":"Name","value":"fromPath"}},{"kind":"Field","name":{"kind":"Name","value":"toPath"}},{"kind":"Field","name":{"kind":"Name","value":"oldName"}},{"kind":"Field","name":{"kind":"Name","value":"newName"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"undoOf"}},{"kind":"Field","name":{"kind":"Name","value":"undoneAt"}},{"kind":"Field","name":{"kind":"Name","value":"undoable"}}]}}]} as unknown as DocumentNode<ActivityFieldsFragment, unknown>;
export const UndoResultFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"UndoResultFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UndoResult"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"done"}},{"kind":"Field","name":{"kind":"Name","value":"failed"}},{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"activity"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ActivityFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"ok"}},{"kind":"Field","name":{"kind":"Name","value":"reason"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ActivityFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Activity"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"batchId"}},{"kind":"Field","name":{"kind":"Name","value":"action"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nodeKind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentPath"}},{"kind":"Field","name":{"kind":"Name","value":"fromPath"}},{"kind":"Field","name":{"kind":"Name","value":"toPath"}},{"kind":"Field","name":{"kind":"Name","value":"oldName"}},{"kind":"Field","name":{"kind":"Name","value":"newName"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"undoOf"}},{"kind":"Field","name":{"kind":"Name","value":"undoneAt"}},{"kind":"Field","name":{"kind":"Name","value":"undoable"}}]}}]} as unknown as DocumentNode<UndoResultFieldsFragment, unknown>;
export const ClipFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<ClipFieldsFragment, unknown>;
export const FileRequestFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FileRequestFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequest"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"requireName"}},{"kind":"Field","name":{"kind":"Name","value":"groupByUploader"}},{"kind":"Field","name":{"kind":"Name","value":"maxFileSize"}},{"kind":"Field","name":{"kind":"Name","value":"maxTotalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"maxFiles"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"fileCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"uploaderCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"lastUploadAt"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<FileRequestFieldsFragment, unknown>;
export const CollectSessionFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"CollectSessionFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UploadSession"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]} as unknown as DocumentNode<CollectSessionFieldsFragment, unknown>;
export const FeedFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<FeedFieldsFragment, unknown>;
export const FeedItemFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedItemFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FeedItem"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureUrl"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureType"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureSize"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"state"}},{"kind":"Field","name":{"kind":"Name","value":"error"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"jobId"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<FeedItemFieldsFragment, unknown>;
export const JobFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<JobFieldsFragment, unknown>;
export const LinkUsageFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}}]} as unknown as DocumentNode<LinkUsageFieldsFragment, unknown>;
export const DirectLinkFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}}]} as unknown as DocumentNode<DirectLinkFieldsFragment, unknown>;
export const LinkPrefsFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkPrefsFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkPrefs"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"imageFolder"}},{"kind":"Field","name":{"kind":"Name","value":"stripGps"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCapDefault"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesUsed"}}]}}]} as unknown as DocumentNode<LinkPrefsFieldsFragment, unknown>;
export const PlayProgressFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"PlayProgressFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"PlayProgress"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"position"}},{"kind":"Field","name":{"kind":"Name","value":"duration"}},{"kind":"Field","name":{"kind":"Name","value":"finished"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]} as unknown as DocumentNode<PlayProgressFieldsFragment, unknown>;
export const NotifyChannelFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"NotifyChannelFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannel"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"events"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"topic"}},{"kind":"Field","name":{"kind":"Name","value":"hasSecret"}},{"kind":"Field","name":{"kind":"Name","value":"secretHint"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"lastErrorAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<NotifyChannelFieldsFragment, unknown>;
export const SpaceFileFieldsFragmentDoc = {"kind":"Document","definitions":[{"kind":"FragmentDefinition","name":{"kind":"Name","value":"SpaceFileFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"SpaceFile"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"category"}}]}}]} as unknown as DocumentNode<SpaceFileFieldsFragment, unknown>;
export const ActivitiesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Activities"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"filter"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ActivityFilter"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"activities"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"filter"},"value":{"kind":"Variable","name":{"kind":"Name","value":"filter"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ActivityFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ActivityFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Activity"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"batchId"}},{"kind":"Field","name":{"kind":"Name","value":"action"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nodeKind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentPath"}},{"kind":"Field","name":{"kind":"Name","value":"fromPath"}},{"kind":"Field","name":{"kind":"Name","value":"toPath"}},{"kind":"Field","name":{"kind":"Name","value":"oldName"}},{"kind":"Field","name":{"kind":"Name","value":"newName"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"undoOf"}},{"kind":"Field","name":{"kind":"Name","value":"undoneAt"}},{"kind":"Field","name":{"kind":"Name","value":"undoable"}}]}}]} as unknown as DocumentNode<ActivitiesQuery, ActivitiesQueryVariables>;
export const ActivityActorsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ActivityActors"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"activityActors"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"total"}},{"kind":"Field","name":{"kind":"Name","value":"lastAt"}}]}}]}}]} as unknown as DocumentNode<ActivityActorsQuery, ActivityActorsQueryVariables>;
export const UndoPreviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"UndoPreview"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"selection"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"UndoSelection"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"undoPreview"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"selection"},"value":{"kind":"Variable","name":{"kind":"Name","value":"selection"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"UndoResultFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ActivityFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Activity"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"batchId"}},{"kind":"Field","name":{"kind":"Name","value":"action"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nodeKind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentPath"}},{"kind":"Field","name":{"kind":"Name","value":"fromPath"}},{"kind":"Field","name":{"kind":"Name","value":"toPath"}},{"kind":"Field","name":{"kind":"Name","value":"oldName"}},{"kind":"Field","name":{"kind":"Name","value":"newName"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"undoOf"}},{"kind":"Field","name":{"kind":"Name","value":"undoneAt"}},{"kind":"Field","name":{"kind":"Name","value":"undoable"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"UndoResultFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UndoResult"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"done"}},{"kind":"Field","name":{"kind":"Name","value":"failed"}},{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"activity"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ActivityFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"ok"}},{"kind":"Field","name":{"kind":"Name","value":"reason"}}]}}]}}]} as unknown as DocumentNode<UndoPreviewQuery, UndoPreviewQueryVariables>;
export const UndoActivitiesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"UndoActivities"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"selection"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"UndoSelection"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"undoActivities"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"selection"},"value":{"kind":"Variable","name":{"kind":"Name","value":"selection"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"UndoResultFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ActivityFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Activity"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"actor"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ref"}},{"kind":"Field","name":{"kind":"Name","value":"label"}}]}},{"kind":"Field","name":{"kind":"Name","value":"batchId"}},{"kind":"Field","name":{"kind":"Name","value":"action"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nodeKind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentPath"}},{"kind":"Field","name":{"kind":"Name","value":"fromPath"}},{"kind":"Field","name":{"kind":"Name","value":"toPath"}},{"kind":"Field","name":{"kind":"Name","value":"oldName"}},{"kind":"Field","name":{"kind":"Name","value":"newName"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"undoOf"}},{"kind":"Field","name":{"kind":"Name","value":"undoneAt"}},{"kind":"Field","name":{"kind":"Name","value":"undoable"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"UndoResultFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UndoResult"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"done"}},{"kind":"Field","name":{"kind":"Name","value":"failed"}},{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"activity"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ActivityFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"ok"}},{"kind":"Field","name":{"kind":"Name","value":"reason"}}]}}]}}]} as unknown as DocumentNode<UndoActivitiesMutation, UndoActivitiesMutationVariables>;
export const AdminUsersDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminUsers"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminUsers"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<AdminUsersQuery, AdminUsersQueryVariables>;
export const AdminOverviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"userCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalUsedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"blobCount"}},{"kind":"Field","name":{"kind":"Name","value":"blobBytes"}},{"kind":"Field","name":{"kind":"Name","value":"taskCounts"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"count"}}]}},{"kind":"Field","name":{"kind":"Name","value":"contentIndex"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"done"}},{"kind":"Field","name":{"kind":"Name","value":"empty"}},{"kind":"Field","name":{"kind":"Name","value":"truncated"}},{"kind":"Field","name":{"kind":"Name","value":"unsupported"}},{"kind":"Field","name":{"kind":"Name","value":"failed"}},{"kind":"Field","name":{"kind":"Name","value":"pending"}},{"kind":"Field","name":{"kind":"Name","value":"textBytes"}}]}}]}}]}}]} as unknown as DocumentNode<AdminOverviewQuery, AdminOverviewQueryVariables>;
export const AdminCreateUserDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminCreateUser"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminCreateUser"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}},{"kind":"Argument","name":{"kind":"Name","value":"quotaBytes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}}]}}]}}]} as unknown as DocumentNode<AdminCreateUserMutation, AdminCreateUserMutationVariables>;
export const AdminSetQuotaDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminSetQuota"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminSetQuota"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}},{"kind":"Argument","name":{"kind":"Name","value":"quotaBytes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}}]}}]}}]} as unknown as DocumentNode<AdminSetQuotaMutation, AdminSetQuotaMutationVariables>;
export const AdminSetDisabledDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminSetDisabled"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"disabled"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminSetDisabled"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}},{"kind":"Argument","name":{"kind":"Name","value":"disabled"},"value":{"kind":"Variable","name":{"kind":"Name","value":"disabled"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}}]}}]}}]} as unknown as DocumentNode<AdminSetDisabledMutation, AdminSetDisabledMutationVariables>;
export const AdminResetPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminResetPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminResetPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}}]}]}}]} as unknown as DocumentNode<AdminResetPasswordMutation, AdminResetPasswordMutationVariables>;
export const AdminRetryFailedTasksDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminRetryFailedTasks"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminRetryFailedTasks"}}]}}]} as unknown as DocumentNode<AdminRetryFailedTasksMutation, AdminRetryFailedTasksMutationVariables>;
export const AppPasswordsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AppPasswords"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"appPasswords"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]} as unknown as DocumentNode<AppPasswordsQuery, AppPasswordsQueryVariables>;
export const CreateAppPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateAppPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createAppPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}]}]}}]} as unknown as DocumentNode<CreateAppPasswordMutation, CreateAppPasswordMutationVariables>;
export const RevokeAppPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeAppPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeAppPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeAppPasswordMutation, RevokeAppPasswordMutationVariables>;
export const ArchiveInfoDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ArchiveInfo"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"archiveInfo"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"format"}},{"kind":"Field","name":{"kind":"Name","value":"entryCount"}},{"kind":"Field","name":{"kind":"Name","value":"fileCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalSize"}},{"kind":"Field","name":{"kind":"Name","value":"solid"}},{"kind":"Field","name":{"kind":"Name","value":"encrypted"}},{"kind":"Field","name":{"kind":"Name","value":"error"}}]}}]}}]} as unknown as DocumentNode<ArchiveInfoQuery, ArchiveInfoQueryVariables>;
export const ArchiveEntriesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ArchiveEntries"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"path"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"archiveEntries"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"path"},"value":{"kind":"Variable","name":{"kind":"Name","value":"path"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"idx"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"isDir"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"modifiedAt"}},{"kind":"Field","name":{"kind":"Name","value":"flag"}},{"kind":"Field","name":{"kind":"Name","value":"previewable"}},{"kind":"Field","name":{"kind":"Name","value":"downloadable"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<ArchiveEntriesQuery, ArchiveEntriesQueryVariables>;
export const ExtractArchiveDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ExtractArchive"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"base"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"paths"}},"type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"extractArchive"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"base"},"value":{"kind":"Variable","name":{"kind":"Name","value":"base"}}},{"kind":"Argument","name":{"kind":"Name","value":"paths"},"value":{"kind":"Variable","name":{"kind":"Name","value":"paths"}}},{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}}]}}]}}]} as unknown as DocumentNode<ExtractArchiveMutation, ExtractArchiveMutationVariables>;
export const LoginDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Login"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"login"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<LoginMutation, LoginMutationVariables>;
export const RegisterDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Register"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"register"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<RegisterMutation, RegisterMutationVariables>;
export const RefreshDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Refresh"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refresh"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<RefreshMutation, RefreshMutationVariables>;
export const LogoutDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Logout"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"logout"}}]}}]} as unknown as DocumentNode<LogoutMutation, LogoutMutationVariables>;
export const MeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Me"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"me"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}},{"kind":"Field","name":{"kind":"Name","value":"unreadNotifications"}}]}}]}}]} as unknown as DocumentNode<MeQuery, MeQueryVariables>;
export const ChangePasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ChangePassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"oldPassword"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"newPassword"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"changePassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"oldPassword"},"value":{"kind":"Variable","name":{"kind":"Name","value":"oldPassword"}}},{"kind":"Argument","name":{"kind":"Name","value":"newPassword"},"value":{"kind":"Variable","name":{"kind":"Name","value":"newPassword"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<ChangePasswordMutation, ChangePasswordMutationVariables>;
export const ChatMessagesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ChatMessages"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"before"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"after"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"query"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"chatMessages"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}},{"kind":"Argument","name":{"kind":"Name","value":"before"},"value":{"kind":"Variable","name":{"kind":"Name","value":"before"}}},{"kind":"Argument","name":{"kind":"Name","value":"after"},"value":{"kind":"Variable","name":{"kind":"Name","value":"after"}}},{"kind":"Argument","name":{"kind":"Name","value":"query"},"value":{"kind":"Variable","name":{"kind":"Name","value":"query"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"body"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}}]}}]}}]} as unknown as DocumentNode<ChatMessagesQuery, ChatMessagesQueryVariables>;
export const SendChatMessageDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"SendChatMessage"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"body"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"sendChatMessage"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"body"},"value":{"kind":"Variable","name":{"kind":"Name","value":"body"}}},{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"body"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}}]}}]}}]} as unknown as DocumentNode<SendChatMessageMutation, SendChatMessageMutationVariables>;
export const DeleteChatMessageDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DeleteChatMessage"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"deleteChatMessage"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<DeleteChatMessageMutation, DeleteChatMessageMutationVariables>;
export const EnsureChatFolderDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"EnsureChatFolder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"ensureChatFolder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<EnsureChatFolderMutation, EnsureChatFolderMutationVariables>;
export const ChatFolderInfoDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ChatFolderInfo"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"chatFolder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<ChatFolderInfoQuery, ChatFolderInfoQueryVariables>;
export const ClipsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Clips"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"unreadOnly"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"site"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clips"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"unreadOnly"},"value":{"kind":"Variable","name":{"kind":"Name","value":"unreadOnly"}}},{"kind":"Argument","name":{"kind":"Name","value":"site"},"value":{"kind":"Variable","name":{"kind":"Name","value":"site"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ClipFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<ClipsQuery, ClipsQueryVariables>;
export const ClipSitesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ClipSites"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clipSites"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<ClipSitesQuery, ClipSitesQueryVariables>;
export const ClipDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Clip"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clip"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ClipFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<ClipQuery, ClipQueryVariables>;
export const NodeClipDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodeClip"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"nodeClip"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ClipFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<NodeClipQuery, NodeClipQueryVariables>;
export const ClipPrefsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ClipPrefs"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clipPrefs"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"folder"}},{"kind":"Field","name":{"kind":"Name","value":"byMonth"}}]}}]}}]} as unknown as DocumentNode<ClipPrefsQuery, ClipPrefsQueryVariables>;
export const ClipUrlDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ClipUrl"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"url"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"html"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"force"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clipUrl"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"url"},"value":{"kind":"Variable","name":{"kind":"Name","value":"url"}}},{"kind":"Argument","name":{"kind":"Name","value":"html"},"value":{"kind":"Variable","name":{"kind":"Name","value":"html"}}},{"kind":"Argument","name":{"kind":"Name","value":"force"},"value":{"kind":"Variable","name":{"kind":"Name","value":"force"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"job"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"existing"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ClipFields"}}]}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<ClipUrlMutation, ClipUrlMutationVariables>;
export const MarkClipReadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"MarkClipRead"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"read"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"markClipRead"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"read"},"value":{"kind":"Variable","name":{"kind":"Name","value":"read"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"ClipFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"ClipFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Clip"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"site"}},{"kind":"Field","name":{"kind":"Name","value":"byline"}},{"kind":"Field","name":{"kind":"Name","value":"excerpt"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"words"}},{"kind":"Field","name":{"kind":"Name","value":"images"}},{"kind":"Field","name":{"kind":"Name","value":"readAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<MarkClipReadMutation, MarkClipReadMutationVariables>;
export const SetClipPrefsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"SetClipPrefs"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ClipPrefsInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"setClipPrefs"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"folder"}},{"kind":"Field","name":{"kind":"Name","value":"byMonth"}}]}}]}}]} as unknown as DocumentNode<SetClipPrefsMutation, SetClipPrefsMutationVariables>;
export const MyFileRequestsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MyFileRequests"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"myFileRequests"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FileRequestFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FileRequestFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequest"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"requireName"}},{"kind":"Field","name":{"kind":"Name","value":"groupByUploader"}},{"kind":"Field","name":{"kind":"Name","value":"maxFileSize"}},{"kind":"Field","name":{"kind":"Name","value":"maxTotalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"maxFiles"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"fileCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"uploaderCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"lastUploadAt"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<MyFileRequestsQuery, MyFileRequestsQueryVariables>;
export const CreateFileRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateFileRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"folderId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequestInput"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createFileRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"folderId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"folderId"}}},{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FileRequestFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FileRequestFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequest"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"requireName"}},{"kind":"Field","name":{"kind":"Name","value":"groupByUploader"}},{"kind":"Field","name":{"kind":"Name","value":"maxFileSize"}},{"kind":"Field","name":{"kind":"Name","value":"maxTotalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"maxFiles"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"fileCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"uploaderCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"lastUploadAt"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<CreateFileRequestMutation, CreateFileRequestMutationVariables>;
export const UpdateFileRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"UpdateFileRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequestInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"updateFileRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FileRequestFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FileRequestFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FileRequest"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"requireName"}},{"kind":"Field","name":{"kind":"Name","value":"groupByUploader"}},{"kind":"Field","name":{"kind":"Name","value":"maxFileSize"}},{"kind":"Field","name":{"kind":"Name","value":"maxTotalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"maxFiles"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"fileCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"uploaderCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"lastUploadAt"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<UpdateFileRequestMutation, UpdateFileRequestMutationVariables>;
export const RevokeFileRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeFileRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeFileRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeFileRequestMutation, RevokeFileRequestMutationVariables>;
export const MarkFileRequestSeenDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"MarkFileRequestSeen"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"markFileRequestSeen"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<MarkFileRequestSeenMutation, MarkFileRequestSeenMutationVariables>;
export const FileRequestUploadsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"FileRequestUploads"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"fileRequestUploads"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"fileName"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"uploaderName"}},{"kind":"Field","name":{"kind":"Name","value":"ip"}},{"kind":"Field","name":{"kind":"Name","value":"userAgent"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}}]} as unknown as DocumentNode<FileRequestUploadsQuery, FileRequestUploadsQueryVariables>;
export const FileRequestInfoDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"FileRequestInfo"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"fileRequestInfo"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"note"}},{"kind":"Field","name":{"kind":"Name","value":"needPassword"}},{"kind":"Field","name":{"kind":"Name","value":"needName"}},{"kind":"Field","name":{"kind":"Name","value":"maxFileSize"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"closed"}}]}}]}}]} as unknown as DocumentNode<FileRequestInfoQuery, FileRequestInfoQueryVariables>;
export const OpenFileRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"OpenFileRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"uploaderName"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"openFileRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}},{"kind":"Argument","name":{"kind":"Name","value":"uploaderName"},"value":{"kind":"Variable","name":{"kind":"Name","value":"uploaderName"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}}]}}]}}]} as unknown as DocumentNode<OpenFileRequestMutation, OpenFileRequestMutationVariables>;
export const CollectInitUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CollectInitUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"size"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"collectInitUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}},{"kind":"Argument","name":{"kind":"Name","value":"size"},"value":{"kind":"Variable","name":{"kind":"Name","value":"size"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"CollectSessionFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"CollectSessionFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UploadSession"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]} as unknown as DocumentNode<CollectInitUploadMutation, CollectInitUploadMutationVariables>;
export const CollectUploadSessionDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"CollectUploadSession"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"collectUploadSession"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"CollectSessionFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"CollectSessionFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"UploadSession"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]} as unknown as DocumentNode<CollectUploadSessionQuery, CollectUploadSessionQueryVariables>;
export const CollectCompleteUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CollectCompleteUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"etags"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"PartEtag"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"collectCompleteUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}},{"kind":"Argument","name":{"kind":"Name","value":"etags"},"value":{"kind":"Variable","name":{"kind":"Name","value":"etags"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<CollectCompleteUploadMutation, CollectCompleteUploadMutationVariables>;
export const CollectAbortUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CollectAbortUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"collectAbortUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}}]}]}}]} as unknown as DocumentNode<CollectAbortUploadMutation, CollectAbortUploadMutationVariables>;
export const CollectMyUploadsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"CollectMyUploads"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"collectMyUploads"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<CollectMyUploadsQuery, CollectMyUploadsQueryVariables>;
export const FeedsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Feeds"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"feeds"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<FeedsQuery, FeedsQueryVariables>;
export const FeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Feed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"feed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<FeedQuery, FeedQueryVariables>;
export const FeedItemsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"FeedItems"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"feedId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"feedItems"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"feedId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"feedId"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedItemFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedItemFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FeedItem"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureUrl"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureType"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureSize"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"state"}},{"kind":"Field","name":{"kind":"Name","value":"error"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"jobId"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<FeedItemsQuery, FeedItemsQueryVariables>;
export const PreviewFeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"PreviewFeed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"url"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"assetPattern"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"prerelease"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"previewFeed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"url"},"value":{"kind":"Variable","name":{"kind":"Name","value":"url"}}},{"kind":"Argument","name":{"kind":"Name","value":"assetPattern"},"value":{"kind":"Variable","name":{"kind":"Name","value":"assetPattern"}}},{"kind":"Argument","name":{"kind":"Name","value":"prerelease"},"value":{"kind":"Variable","name":{"kind":"Name","value":"prerelease"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"podcast"}},{"kind":"Field","name":{"kind":"Name","value":"suggestedMode"}},{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureUrl"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureSize"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}}]}}]}}]}}]} as unknown as DocumentNode<PreviewFeedQuery, PreviewFeedQueryVariables>;
export const ExportOpmlDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ExportOpml"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"exportOpml"}}]}}]} as unknown as DocumentNode<ExportOpmlQuery, ExportOpmlQueryVariables>;
export const CreateFeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateFeed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"FeedInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createFeed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<CreateFeedMutation, CreateFeedMutationVariables>;
export const UpdateFeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"UpdateFeed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"FeedInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"updateFeed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<UpdateFeedMutation, UpdateFeedMutationVariables>;
export const DeleteFeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DeleteFeed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"deleteFeed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<DeleteFeedMutation, DeleteFeedMutationVariables>;
export const RefreshFeedDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RefreshFeed"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refreshFeed"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Feed"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"siteUrl"}},{"kind":"Field","name":{"kind":"Name","value":"folder"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}},{"kind":"Field","name":{"kind":"Name","value":"mode"}},{"kind":"Field","name":{"kind":"Name","value":"includeWords"}},{"kind":"Field","name":{"kind":"Name","value":"excludeWords"}},{"kind":"Field","name":{"kind":"Name","value":"assetPattern"}},{"kind":"Field","name":{"kind":"Name","value":"prerelease"}},{"kind":"Field","name":{"kind":"Name","value":"keepLast"}},{"kind":"Field","name":{"kind":"Name","value":"intervalMinutes"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"lastCheckedAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"failCount"}},{"kind":"Field","name":{"kind":"Name","value":"nextCheckAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"itemCount"}},{"kind":"Field","name":{"kind":"Name","value":"newCount"}},{"kind":"Field","name":{"kind":"Name","value":"latestAt"}}]}}]} as unknown as DocumentNode<RefreshFeedMutation, RefreshFeedMutationVariables>;
export const FetchFeedItemDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"FetchFeedItem"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"itemId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"mode"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"FeedMode"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"fetchFeedItem"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"itemId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"itemId"}}},{"kind":"Argument","name":{"kind":"Name","value":"mode"},"value":{"kind":"Variable","name":{"kind":"Name","value":"mode"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"FeedItemFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"FeedItemFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"FeedItem"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureUrl"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureType"}},{"kind":"Field","name":{"kind":"Name","value":"enclosureSize"}},{"kind":"Field","name":{"kind":"Name","value":"publishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"state"}},{"kind":"Field","name":{"kind":"Name","value":"error"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"jobId"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<FetchFeedItemMutation, FetchFeedItemMutationVariables>;
export const ImportOpmlDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ImportOpml"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"opml"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"importOpml"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"opml"},"value":{"kind":"Variable","name":{"kind":"Name","value":"opml"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"added"}},{"kind":"Field","name":{"kind":"Name","value":"existed"}},{"kind":"Field","name":{"kind":"Name","value":"failed"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"reason"}}]}}]}}]}}]} as unknown as DocumentNode<ImportOpmlMutation, ImportOpmlMutationVariables>;
export const MyJobsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MyJobs"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"active"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"myJobs"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"active"},"value":{"kind":"Variable","name":{"kind":"Name","value":"active"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<MyJobsQuery, MyJobsQueryVariables>;
export const JobDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Job"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"job"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<JobQuery, JobQueryVariables>;
export const FetchEnabledDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"FetchEnabled"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"fetchEnabled"}}]}}]} as unknown as DocumentNode<FetchEnabledQuery, FetchEnabledQueryVariables>;
export const FetchUrlsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"FetchUrls"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"urls"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"fetchUrls"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"urls"},"value":{"kind":"Variable","name":{"kind":"Name","value":"urls"}}},{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<FetchUrlsMutation, FetchUrlsMutationVariables>;
export const CancelJobDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CancelJob"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"cancelJob"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<CancelJobMutation, CancelJobMutationVariables>;
export const RetryJobDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RetryJob"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"retryJob"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"JobFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"JobFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"Job"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"bytesDone"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesDone"}},{"kind":"Field","name":{"kind":"Name","value":"filesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"filesSkipped"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"errorMessage"}},{"kind":"Field","name":{"kind":"Name","value":"attempts"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"startedAt"}},{"kind":"Field","name":{"kind":"Name","value":"finishedAt"}},{"kind":"Field","name":{"kind":"Name","value":"result"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}}]}}]} as unknown as DocumentNode<RetryJobMutation, RetryJobMutationVariables>;
export const ClearFinishedJobsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ClearFinishedJobs"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clearFinishedJobs"}}]}}]} as unknown as DocumentNode<ClearFinishedJobsMutation, ClearFinishedJobsMutationVariables>;
export const LinksEnabledDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"LinksEnabled"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"linksEnabled"}}]}}]} as unknown as DocumentNode<LinksEnabledQuery, LinksEnabledQueryVariables>;
export const MyLinksDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MyLinks"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"myLinks"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"DirectLinkFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}}]} as unknown as DocumentNode<MyLinksQuery, MyLinksQueryVariables>;
export const LinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Link"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"link"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"DirectLinkFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}}]} as unknown as DocumentNode<LinkQuery, LinkQueryVariables>;
export const LinkStatsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"LinkStats"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"hours"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"linkStats"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"hours"},"value":{"kind":"Variable","name":{"kind":"Name","value":"hours"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hour"}},{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}},{"kind":"Field","name":{"kind":"Name","value":"ips"}}]}}]}}]} as unknown as DocumentNode<LinkStatsQuery, LinkStatsQueryVariables>;
export const LinkReferersDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"LinkReferers"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"days"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"linkReferers"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"days"},"value":{"kind":"Variable","name":{"kind":"Name","value":"days"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"host"}},{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}}]}}]}}]} as unknown as DocumentNode<LinkReferersQuery, LinkReferersQueryVariables>;
export const LinkHitsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"LinkHits"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"linkHits"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"at"}},{"kind":"Field","name":{"kind":"Name","value":"ip"}},{"kind":"Field","name":{"kind":"Name","value":"userAgent"}},{"kind":"Field","name":{"kind":"Name","value":"referer"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}}]} as unknown as DocumentNode<LinkHitsQuery, LinkHitsQueryVariables>;
export const LinkPrefsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"LinkPrefs"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"linkPrefs"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkPrefsFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkPrefsFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkPrefs"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"imageFolder"}},{"kind":"Field","name":{"kind":"Name","value":"stripGps"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCapDefault"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesUsed"}}]}}]} as unknown as DocumentNode<LinkPrefsQuery, LinkPrefsQueryVariables>;
export const CreateLinksDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateLinks"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeIds"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createLinks"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeIds"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeIds"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"DirectLinkFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}}]} as unknown as DocumentNode<CreateLinksMutation, CreateLinksMutationVariables>;
export const UpdateLinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"UpdateLink"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLinkInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"updateLink"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"DirectLinkFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}}]} as unknown as DocumentNode<UpdateLinkMutation, UpdateLinkMutationVariables>;
export const PauseLinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"PauseLink"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"paused"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"pauseLink"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"paused"},"value":{"kind":"Variable","name":{"kind":"Name","value":"paused"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"DirectLinkFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkUsageFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkUsage"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}},{"kind":"Field","name":{"kind":"Name","value":"blocked"}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"DirectLinkFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"DirectLink"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"pauseReason"}},{"kind":"Field","name":{"kind":"Name","value":"source"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"autoPause"}},{"kind":"Field","name":{"kind":"Name","value":"hasGps"}},{"kind":"Field","name":{"kind":"Name","value":"hitsTotal"}},{"kind":"Field","name":{"kind":"Name","value":"bytesTotal"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"today"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}},{"kind":"Field","name":{"kind":"Name","value":"last30Days"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkUsageFields"}}]}}]}}]} as unknown as DocumentNode<PauseLinkMutation, PauseLinkMutationVariables>;
export const RevokeLinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeLink"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeLink"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeLinkMutation, RevokeLinkMutationVariables>;
export const SetLinkPrefsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"SetLinkPrefs"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"LinkPrefsInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"setLinkPrefs"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"LinkPrefsFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"LinkPrefsFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"LinkPrefs"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refererHosts"}},{"kind":"Field","name":{"kind":"Name","value":"allowNoReferer"}},{"kind":"Field","name":{"kind":"Name","value":"imageFolder"}},{"kind":"Field","name":{"kind":"Name","value":"stripGps"}},{"kind":"Field","name":{"kind":"Name","value":"dailyBytesCapDefault"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesUsed"}}]}}]} as unknown as DocumentNode<SetLinkPrefsMutation, SetLinkPrefsMutationVariables>;
export const AdminLinkStatsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminLinkStats"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminLinkStats"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"monthHits"}},{"kind":"Field","name":{"kind":"Name","value":"monthBytes"}},{"kind":"Field","name":{"kind":"Name","value":"monthlyBytesCap"}},{"kind":"Field","name":{"kind":"Name","value":"top"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"owner"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"monthHits"}},{"kind":"Field","name":{"kind":"Name","value":"monthBytes"}}]}}]}}]}}]} as unknown as DocumentNode<AdminLinkStatsQuery, AdminLinkStatsQueryVariables>;
export const AdminPauseLinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminPauseLink"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"paused"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminPauseLink"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"paused"},"value":{"kind":"Variable","name":{"kind":"Name","value":"paused"}}}]}]}}]} as unknown as DocumentNode<AdminPauseLinkMutation, AdminPauseLinkMutationVariables>;
export const AdminRevokeLinkDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminRevokeLink"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminRevokeLink"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<AdminRevokeLinkMutation, AdminRevokeLinkMutationVariables>;
export const NodeThumbDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodeThumb"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}}]}}]} as unknown as DocumentNode<NodeThumbQuery, NodeThumbQueryVariables>;
export const McpapiKeysDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MCPAPIKeys"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"mcpAPIKeys"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]} as unknown as DocumentNode<McpapiKeysQuery, McpapiKeysQueryVariables>;
export const CreateMcpapiKeyDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateMCPAPIKey"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"scopes"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createMCPAPIKey"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}},{"kind":"Argument","name":{"kind":"Name","value":"scopes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"scopes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"credential"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]}}]} as unknown as DocumentNode<CreateMcpapiKeyMutation, CreateMcpapiKeyMutationVariables>;
export const RevokeMcpapiKeyDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeMCPAPIKey"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeMCPAPIKey"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeMcpapiKeyMutation, RevokeMcpapiKeyMutationVariables>;
export const OAuthGrantsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"OAuthGrants"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"oauthGrants"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"clientId"}},{"kind":"Field","name":{"kind":"Name","value":"clientName"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<OAuthGrantsQuery, OAuthGrantsQueryVariables>;
export const RevokeOAuthGrantDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeOAuthGrant"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeOAuthGrant"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeOAuthGrantMutation, RevokeOAuthGrantMutationVariables>;
export const OAuthAuthorizationRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"OAuthAuthorizationRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"OAuthAuthorizationInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"oauthAuthorizationRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clientName"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}}]}}]}}]} as unknown as DocumentNode<OAuthAuthorizationRequestQuery, OAuthAuthorizationRequestQueryVariables>;
export const DecideOAuthAuthorizationDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DecideOAuthAuthorization"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"OAuthAuthorizationInput"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"approved"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"decideOAuthAuthorization"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}},{"kind":"Argument","name":{"kind":"Name","value":"approved"},"value":{"kind":"Variable","name":{"kind":"Name","value":"approved"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"redirectUrl"}}]}}]}}]} as unknown as DocumentNode<DecideOAuthAuthorizationMutation, DecideOAuthAuthorizationMutationVariables>;
export const McpAccessRootsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"McpAccessRoots"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"mcpAccessRoots"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"credentialId"}},{"kind":"Field","name":{"kind":"Name","value":"credentialType"}},{"kind":"Field","name":{"kind":"Name","value":"rootId"}},{"kind":"Field","name":{"kind":"Name","value":"rootName"}}]}}]}}]} as unknown as DocumentNode<McpAccessRootsQuery, McpAccessRootsQueryVariables>;
export const SetMcpAccessRootDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"SetMcpAccessRoot"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"credentialId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"credentialType"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"rootPath"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"setMCPAccessRoot"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"credentialId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"credentialId"}}},{"kind":"Argument","name":{"kind":"Name","value":"credentialType"},"value":{"kind":"Variable","name":{"kind":"Name","value":"credentialType"}}},{"kind":"Argument","name":{"kind":"Name","value":"rootPath"},"value":{"kind":"Variable","name":{"kind":"Name","value":"rootPath"}}}]}]}}]} as unknown as DocumentNode<SetMcpAccessRootMutation, SetMcpAccessRootMutationVariables>;
export const McpAuditDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"McpAudit"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"beforeId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"mcpAudit"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"beforeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"beforeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"credentialId"}},{"kind":"Field","name":{"kind":"Name","value":"credentialType"}},{"kind":"Field","name":{"kind":"Name","value":"endpoint"}},{"kind":"Field","name":{"kind":"Name","value":"tool"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"errorCode"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<McpAuditQuery, McpAuditQueryVariables>;
export const PlaylistDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Playlist"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"playlist"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}}]}}]}}]} as unknown as DocumentNode<PlaylistQuery, PlaylistQueryVariables>;
export const RecentPlaysDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"RecentPlays"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"recentPlays"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"progress"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"PlayProgressFields"}}]}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"PlayProgressFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"PlayProgress"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"position"}},{"kind":"Field","name":{"kind":"Name","value":"duration"}},{"kind":"Field","name":{"kind":"Name","value":"finished"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]} as unknown as DocumentNode<RecentPlaysQuery, RecentPlaysQueryVariables>;
export const SaveProgressDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"SaveProgress"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"position"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Float"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"duration"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Float"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"saveProgress"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"position"},"value":{"kind":"Variable","name":{"kind":"Name","value":"position"}}},{"kind":"Argument","name":{"kind":"Name","value":"duration"},"value":{"kind":"Variable","name":{"kind":"Name","value":"duration"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"PlayProgressFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"PlayProgressFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"PlayProgress"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"position"}},{"kind":"Field","name":{"kind":"Name","value":"duration"}},{"kind":"Field","name":{"kind":"Name","value":"finished"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]} as unknown as DocumentNode<SaveProgressMutation, SaveProgressMutationVariables>;
export const ClearProgressDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ClearProgress"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clearProgress"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}]}]}}]} as unknown as DocumentNode<ClearProgressMutation, ClearProgressMutationVariables>;
export const ChildrenDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Children"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"order"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"NodeOrder"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"desc"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"children"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}},{"kind":"Argument","name":{"kind":"Name","value":"order"},"value":{"kind":"Variable","name":{"kind":"Name","value":"order"}}},{"kind":"Argument","name":{"kind":"Name","value":"desc"},"value":{"kind":"Variable","name":{"kind":"Name","value":"desc"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeBytes"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeCount"}},{"kind":"Field","name":{"kind":"Name","value":"statsStale"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<ChildrenQuery, ChildrenQueryVariables>;
export const NodePreviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodePreview"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}},{"kind":"Field","name":{"kind":"Name","value":"largeUrl"}},{"kind":"Field","name":{"kind":"Name","value":"contentUrl"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"durationSec"}},{"kind":"Field","name":{"kind":"Name","value":"markdown"}},{"kind":"Field","name":{"kind":"Name","value":"subtitles"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"label"}},{"kind":"Field","name":{"kind":"Name","value":"lang"}},{"kind":"Field","name":{"kind":"Name","value":"default"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"playProgress"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"position"}},{"kind":"Field","name":{"kind":"Name","value":"duration"}},{"kind":"Field","name":{"kind":"Name","value":"finished"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]}}]} as unknown as DocumentNode<NodePreviewQuery, NodePreviewQueryVariables>;
export const RequestPreviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RequestPreview"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"requestPreview"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"contentUrl"}}]}}]}}]} as unknown as DocumentNode<RequestPreviewMutation, RequestPreviewMutationVariables>;
export const NodeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Node"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<NodeQuery, NodeQueryVariables>;
export const NodeDetailDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodeDetail"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"sha256"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeBytes"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeCount"}},{"kind":"Field","name":{"kind":"Name","value":"statsStale"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}}]}}]} as unknown as DocumentNode<NodeDetailQuery, NodeDetailQueryVariables>;
export const CreateFolderDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateFolder"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createFolder"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<CreateFolderMutation, CreateFolderMutationVariables>;
export const RenameNodeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RenameNode"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"renameNode"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<RenameNodeMutation, RenameNodeMutationVariables>;
export const MoveNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"MoveNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"moveNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}},{"kind":"Argument","name":{"kind":"Name","value":"targetParentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<MoveNodesMutation, MoveNodesMutationVariables>;
export const DeleteNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DeleteNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"deleteNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}]}]}}]} as unknown as DocumentNode<DeleteNodesMutation, DeleteNodesMutationVariables>;
export const CopyNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CopyNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"copyNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}},{"kind":"Argument","name":{"kind":"Name","value":"targetParentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<CopyNodesMutation, CopyNodesMutationVariables>;
export const SearchNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SearchNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"q"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"searchNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"q"},"value":{"kind":"Variable","name":{"kind":"Name","value":"q"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<SearchNodesQuery, SearchNodesQueryVariables>;
export const SearchContentDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SearchContent"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"q"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"searchContent"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"q"},"value":{"kind":"Variable","name":{"kind":"Name","value":"q"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"snippet"}},{"kind":"Field","name":{"kind":"Name","value":"highlights"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"start"}},{"kind":"Field","name":{"kind":"Name","value":"length"}}]}},{"kind":"Field","name":{"kind":"Name","value":"page"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}}]} as unknown as DocumentNode<SearchContentQuery, SearchContentQueryVariables>;
export const NotificationsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Notifications"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"unreadOnly"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"notifications"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}},{"kind":"Argument","name":{"kind":"Name","value":"unreadOnly"},"value":{"kind":"Variable","name":{"kind":"Name","value":"unreadOnly"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"level"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"body"}},{"kind":"Field","name":{"kind":"Name","value":"count"}},{"kind":"Field","name":{"kind":"Name","value":"target"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}}]}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"read"}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}}]}}]}}]} as unknown as DocumentNode<NotificationsQuery, NotificationsQueryVariables>;
export const MarkNotificationsReadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"MarkNotificationsRead"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"markNotificationsRead"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}]}]}}]} as unknown as DocumentNode<MarkNotificationsReadMutation, MarkNotificationsReadMutationVariables>;
export const ClearNotificationsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ClearNotifications"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clearNotifications"}}]}}]} as unknown as DocumentNode<ClearNotificationsMutation, ClearNotificationsMutationVariables>;
export const NotifyChannelsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NotifyChannels"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"notifyChannels"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"NotifyChannelFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"NotifyChannelFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannel"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"events"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"topic"}},{"kind":"Field","name":{"kind":"Name","value":"hasSecret"}},{"kind":"Field","name":{"kind":"Name","value":"secretHint"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"lastErrorAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<NotifyChannelsQuery, NotifyChannelsQueryVariables>;
export const NotifyEventKindsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NotifyEventKinds"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"notifyEventKinds"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"label"}},{"kind":"Field","name":{"kind":"Name","value":"defaultPush"}},{"kind":"Field","name":{"kind":"Name","value":"adminOnly"}}]}}]}}]} as unknown as DocumentNode<NotifyEventKindsQuery, NotifyEventKindsQueryVariables>;
export const CreateNotifyChannelDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateNotifyChannel"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannelInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createNotifyChannel"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"NotifyChannelFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"NotifyChannelFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannel"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"events"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"topic"}},{"kind":"Field","name":{"kind":"Name","value":"hasSecret"}},{"kind":"Field","name":{"kind":"Name","value":"secretHint"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"lastErrorAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<CreateNotifyChannelMutation, CreateNotifyChannelMutationVariables>;
export const UpdateNotifyChannelDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"UpdateNotifyChannel"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannelInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"updateNotifyChannel"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"NotifyChannelFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"NotifyChannelFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"NotifyChannel"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"enabled"}},{"kind":"Field","name":{"kind":"Name","value":"events"}},{"kind":"Field","name":{"kind":"Name","value":"url"}},{"kind":"Field","name":{"kind":"Name","value":"topic"}},{"kind":"Field","name":{"kind":"Name","value":"hasSecret"}},{"kind":"Field","name":{"kind":"Name","value":"secretHint"}},{"kind":"Field","name":{"kind":"Name","value":"lastOkAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastError"}},{"kind":"Field","name":{"kind":"Name","value":"lastErrorAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]} as unknown as DocumentNode<UpdateNotifyChannelMutation, UpdateNotifyChannelMutationVariables>;
export const DeleteNotifyChannelDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DeleteNotifyChannel"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"deleteNotifyChannel"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<DeleteNotifyChannelMutation, DeleteNotifyChannelMutationVariables>;
export const TestNotifyChannelDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"TestNotifyChannel"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"testNotifyChannel"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<TestNotifyChannelMutation, TestNotifyChannelMutationVariables>;
export const SessionsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Sessions"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"sessions"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"familyId"}},{"kind":"Field","name":{"kind":"Name","value":"userAgent"}},{"kind":"Field","name":{"kind":"Name","value":"ip"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastSeenAt"}},{"kind":"Field","name":{"kind":"Name","value":"revokedAt"}},{"kind":"Field","name":{"kind":"Name","value":"active"}},{"kind":"Field","name":{"kind":"Name","value":"current"}}]}}]}}]} as unknown as DocumentNode<SessionsQuery, SessionsQueryVariables>;
export const RevokeSessionDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeSession"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"familyId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeSession"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"familyId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"familyId"}}}]}]}}]} as unknown as DocumentNode<RevokeSessionMutation, RevokeSessionMutationVariables>;
export const CreateShareDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateShare"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"expiresAt"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Time"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createShare"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}},{"kind":"Argument","name":{"kind":"Name","value":"expiresAt"},"value":{"kind":"Variable","name":{"kind":"Name","value":"expiresAt"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}}]}}]}}]} as unknown as DocumentNode<CreateShareMutation, CreateShareMutationVariables>;
export const RevokeShareDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeShare"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeShare"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeShareMutation, RevokeShareMutationVariables>;
export const MySharesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MyShares"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"myShares"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"visitStats"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"verifyCount"}},{"kind":"Field","name":{"kind":"Name","value":"downloadCount"}},{"kind":"Field","name":{"kind":"Name","value":"lastVisitAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastVisitKind"}}]}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}}]}}]}}]} as unknown as DocumentNode<MySharesQuery, MySharesQueryVariables>;
export const ShareVisitsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ShareVisits"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"shareId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shareVisits"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"shareId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"shareId"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"ip"}},{"kind":"Field","name":{"kind":"Name","value":"userAgent"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<ShareVisitsQuery, ShareVisitsQueryVariables>;
export const ShareInfoDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ShareInfo"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shareInfo"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"needPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expired"}}]}}]}}]} as unknown as DocumentNode<ShareInfoQuery, ShareInfoQueryVariables>;
export const VerifySharePasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"VerifySharePassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"verifySharePassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}}]}}]}}]} as unknown as DocumentNode<VerifySharePasswordMutation, VerifySharePasswordMutationVariables>;
export const ShareRootDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ShareRoot"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shareRoot"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<ShareRootQuery, ShareRootQueryVariables>;
export const SpaceOverviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"trashFiles"}},{"kind":"Field","name":{"kind":"Name","value":"trashBytes"}},{"kind":"Field","name":{"kind":"Name","value":"byType"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"category"}},{"kind":"Field","name":{"kind":"Name","value":"files"}},{"kind":"Field","name":{"kind":"Name","value":"bytes"}}]}},{"kind":"Field","name":{"kind":"Name","value":"duplicateGroups"}},{"kind":"Field","name":{"kind":"Name","value":"duplicateBytes"}}]}}]}}]} as unknown as DocumentNode<SpaceOverviewQuery, SpaceOverviewQueryVariables>;
export const SpaceFoldersDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceFolders"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceFolders"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"folders"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeBytes"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeCount"}},{"kind":"Field","name":{"kind":"Name","value":"statsStale"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}},{"kind":"Field","name":{"kind":"Name","value":"looseFiles"}},{"kind":"Field","name":{"kind":"Name","value":"looseBytes"}}]}}]}}]} as unknown as DocumentNode<SpaceFoldersQuery, SpaceFoldersQueryVariables>;
export const SpaceLargeFilesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceLargeFiles"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"category"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"FileCategory"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceLargeFiles"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"category"},"value":{"kind":"Variable","name":{"kind":"Name","value":"category"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"SpaceFileFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"SpaceFileFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"SpaceFile"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"category"}}]}}]} as unknown as DocumentNode<SpaceLargeFilesQuery, SpaceLargeFilesQueryVariables>;
export const SpaceStaleFilesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceStaleFiles"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"days"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"minSize"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceStaleFiles"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"days"},"value":{"kind":"Variable","name":{"kind":"Name","value":"days"}}},{"kind":"Argument","name":{"kind":"Name","value":"minSize"},"value":{"kind":"Variable","name":{"kind":"Name","value":"minSize"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"SpaceFileFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"SpaceFileFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"SpaceFile"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"category"}}]}}]} as unknown as DocumentNode<SpaceStaleFilesQuery, SpaceStaleFilesQueryVariables>;
export const SpaceDuplicatesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceDuplicates"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"offset"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceDuplicates"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"offset"},"value":{"kind":"Variable","name":{"kind":"Name","value":"offset"}}},{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"sha256"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"copies"}},{"kind":"Field","name":{"kind":"Name","value":"files"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"SpaceFileFields"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextOffset"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"SpaceFileFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"SpaceFile"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"category"}}]}}]} as unknown as DocumentNode<SpaceDuplicatesQuery, SpaceDuplicatesQueryVariables>;
export const SpaceEmptyFoldersDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceEmptyFolders"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"limit"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceEmptyFolders"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"limit"},"value":{"kind":"Variable","name":{"kind":"Name","value":"limit"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"FragmentSpread","name":{"kind":"Name","value":"SpaceFileFields"}}]}}]}},{"kind":"FragmentDefinition","name":{"kind":"Name","value":"SpaceFileFields"},"typeCondition":{"kind":"NamedType","name":{"kind":"Name","value":"SpaceFile"}},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"mime"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"path"}},{"kind":"Field","name":{"kind":"Name","value":"category"}}]}}]} as unknown as DocumentNode<SpaceEmptyFoldersQuery, SpaceEmptyFoldersQueryVariables>;
export const SpaceIdleDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SpaceIdle"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"spaceIdle"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shares"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastVisitAt"}},{"kind":"Field","name":{"kind":"Name","value":"expired"}}]}},{"kind":"Field","name":{"kind":"Name","value":"links"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"nodeId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastHitAt"}},{"kind":"Field","name":{"kind":"Name","value":"hits"}},{"kind":"Field","name":{"kind":"Name","value":"expired"}}]}},{"kind":"Field","name":{"kind":"Name","value":"requests"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"title"}},{"kind":"Field","name":{"kind":"Name","value":"folderId"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]}}]} as unknown as DocumentNode<SpaceIdleQuery, SpaceIdleQueryVariables>;
export const AdminStorageDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminStorage"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminStorage"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"logicalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"physicalBytes"}},{"kind":"Field","name":{"kind":"Name","value":"blobs"}},{"kind":"Field","name":{"kind":"Name","value":"pendingGcBytes"}},{"kind":"Field","name":{"kind":"Name","value":"derivativeBytes"}},{"kind":"Field","name":{"kind":"Name","value":"derivatives"}},{"kind":"Field","name":{"kind":"Name","value":"textIndexBytes"}},{"kind":"Field","name":{"kind":"Name","value":"activityBytes"}}]}}]}}]} as unknown as DocumentNode<AdminStorageQuery, AdminStorageQueryVariables>;
export const TrashDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Trash"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"trash"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"deletedAt"}},{"kind":"Field","name":{"kind":"Name","value":"purgeAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<TrashQuery, TrashQueryVariables>;
export const RestoreNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RestoreNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"restoreNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<RestoreNodesMutation, RestoreNodesMutationVariables>;
export const PurgeNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"PurgeNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"purgeNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}]}]}}]} as unknown as DocumentNode<PurgeNodesMutation, PurgeNodesMutationVariables>;
export const PurgeTrashDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"PurgeTrash"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"purgeTrash"}}]}}]} as unknown as DocumentNode<PurgeTrashMutation, PurgeTrashMutationVariables>;
export const InitUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"InitUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sha256"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"size"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"initUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}},{"kind":"Argument","name":{"kind":"Name","value":"sha256"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sha256"}}},{"kind":"Argument","name":{"kind":"Name","value":"size"},"value":{"kind":"Variable","name":{"kind":"Name","value":"size"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"instant"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}},{"kind":"Field","name":{"kind":"Name","value":"session"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]}}]}}]} as unknown as DocumentNode<InitUploadMutation, InitUploadMutationVariables>;
export const CompleteUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CompleteUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"etags"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"PartEtag"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"completeUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}},{"kind":"Argument","name":{"kind":"Name","value":"etags"},"value":{"kind":"Variable","name":{"kind":"Name","value":"etags"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<CompleteUploadMutation, CompleteUploadMutationVariables>;
export const AbortUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AbortUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"abortUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}}]}]}}]} as unknown as DocumentNode<AbortUploadMutation, AbortUploadMutationVariables>;
export const UploadSessionDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"UploadSession"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"uploadSession"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]}}]} as unknown as DocumentNode<UploadSessionQuery, UploadSessionQueryVariables>;
export const NodeDownloadUrlDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodeDownloadUrl"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"downloadUrl"}}]}}]}}]} as unknown as DocumentNode<NodeDownloadUrlQuery, NodeDownloadUrlQueryVariables>;