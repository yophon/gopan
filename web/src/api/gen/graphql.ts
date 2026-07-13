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

export type AdminOverview = {
  __typename?: 'AdminOverview';
  blobBytes: Scalars['Int64']['output'];
  blobCount: Scalars['Int']['output'];
  taskCounts: Array<TaskCount>;
  totalUsedBytes: Scalars['Int64']['output'];
  userCount: Scalars['Int']['output'];
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

export type AuthPayload = {
  __typename?: 'AuthPayload';
  accessToken: Scalars['String']['output'];
  user: User;
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

export type Mutation = {
  __typename?: 'Mutation';
  abortUpload: Scalars['Boolean']['output'];
  adminCreateUser: AdminUser;
  adminResetPassword: Scalars['String']['output'];
  adminRetryFailedTasks: Scalars['Int']['output'];
  adminSetDisabled: AdminUser;
  adminSetQuota: AdminUser;
  changePassword: AuthPayload;
  completeUpload: Node;
  copyNodes: Array<Node>;
  createAppPassword: Scalars['String']['output'];
  createFolder: Node;
  createMCPAPIKey: McpapiKeyCreated;
  createShare: Share;
  decideOAuthAuthorization: OAuthAuthorizationResult;
  deleteNodes: Scalars['Boolean']['output'];
  initUpload: UploadInit;
  login: AuthPayload;
  logout: Scalars['Boolean']['output'];
  moveNodes: Array<Node>;
  purgeNodes: Scalars['Boolean']['output'];
  purgeTrash: Scalars['Boolean']['output'];
  refresh: AuthPayload;
  register: AuthPayload;
  renameNode: Node;
  requestPreview: PreviewInfo;
  restoreNodes: Array<Node>;
  revokeAppPassword: Scalars['Boolean']['output'];
  revokeMCPAPIKey: Scalars['Boolean']['output'];
  revokeOAuthGrant: Scalars['Boolean']['output'];
  revokeShare: Scalars['Boolean']['output'];
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


export type MutationAdminResetPasswordArgs = {
  userId: Scalars['ID']['input'];
};


export type MutationAdminSetDisabledArgs = {
  disabled: Scalars['Boolean']['input'];
  userId: Scalars['ID']['input'];
};


export type MutationAdminSetQuotaArgs = {
  quotaBytes: Scalars['Int64']['input'];
  userId: Scalars['ID']['input'];
};


export type MutationChangePasswordArgs = {
  newPassword: Scalars['String']['input'];
  oldPassword: Scalars['String']['input'];
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


export type MutationCreateFolderArgs = {
  name: Scalars['String']['input'];
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationCreateMcpapiKeyArgs = {
  name: Scalars['String']['input'];
  scopes: Array<Scalars['String']['input']>;
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


export type MutationDeleteNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
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


export type MutationMoveNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
  targetParentId?: InputMaybe<Scalars['ID']['input']>;
};


export type MutationPurgeNodesArgs = {
  ids: Array<Scalars['ID']['input']>;
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


export type MutationRevokeAppPasswordArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeMcpapiKeyArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeOAuthGrantArgs = {
  id: Scalars['ID']['input'];
};


export type MutationRevokeShareArgs = {
  id: Scalars['ID']['input'];
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
  preview: PreviewInfo;
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

export type PartEtag = {
  etag: Scalars['String']['input'];
  partNumber: Scalars['Int']['input'];
};

export type PartUrl = {
  __typename?: 'PartUrl';
  partNumber: Scalars['Int']['output'];
  url: Scalars['String']['output'];
};

export type PreviewInfo = {
  __typename?: 'PreviewInfo';
  contentUrl?: Maybe<Scalars['String']['output']>;
  durationSec?: Maybe<Scalars['Int']['output']>;
  kind: PreviewKind;
  largeUrl?: Maybe<Scalars['String']['output']>;
  status?: Maybe<TaskStatus>;
  thumbUrl?: Maybe<Scalars['String']['output']>;
};

export type PreviewKind =
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
  adminOverview: AdminOverview;
  adminUsers: Array<AdminUser>;
  appPasswords: Array<AppPassword>;
  children: NodePage;
  mcpAPIKeys: Array<McpapiKey>;
  me: User;
  myShares: Array<Share>;
  node: Node;
  oauthAuthorizationRequest: OAuthAuthorizationRequest;
  oauthGrants: Array<OAuthGrant>;
  searchNodes: NodePage;
  shareInfo: ShareInfo;
  shareRoot: Node;
  trash: NodePage;
  uploadSession: UploadSession;
};


export type QueryChildrenArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  desc?: InputMaybe<Scalars['Boolean']['input']>;
  order?: InputMaybe<NodeOrder>;
  parentId?: InputMaybe<Scalars['ID']['input']>;
};


export type QueryNodeArgs = {
  id: Scalars['ID']['input'];
};


export type QueryOauthAuthorizationRequestArgs = {
  input: OAuthAuthorizationInput;
};


export type QuerySearchNodesArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
  q: Scalars['String']['input'];
};


export type QueryShareInfoArgs = {
  token: Scalars['String']['input'];
};


export type QueryTrashArgs = {
  cursor?: InputMaybe<Scalars['String']['input']>;
};


export type QueryUploadSessionArgs = {
  id: Scalars['ID']['input'];
};

export type Share = {
  __typename?: 'Share';
  createdAt: Scalars['Time']['output'];
  expiresAt?: Maybe<Scalars['Time']['output']>;
  hasPassword: Scalars['Boolean']['output'];
  id: Scalars['ID']['output'];
  node: Node;
  token: Scalars['String']['output'];
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
  usedBytes: Scalars['Int64']['output'];
  username: Scalars['String']['output'];
};

export type AdminUsersQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminUsersQuery = { __typename?: 'Query', adminUsers: Array<{ __typename?: 'AdminUser', id: string, username: string, isAdmin: boolean, disabled: boolean, quotaBytes: number, usedBytes: number, createdAt: string }> };

export type AdminOverviewQueryVariables = Exact<{ [key: string]: never; }>;


export type AdminOverviewQuery = { __typename?: 'Query', adminOverview: { __typename?: 'AdminOverview', userCount: number, totalUsedBytes: number, blobCount: number, blobBytes: number, taskCounts: Array<{ __typename?: 'TaskCount', status: string, count: number }> } };

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


export type MeQuery = { __typename?: 'Query', me: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } };

export type ChangePasswordMutationVariables = Exact<{
  oldPassword: Scalars['String']['input'];
  newPassword: Scalars['String']['input'];
}>;


export type ChangePasswordMutation = { __typename?: 'Mutation', changePassword: { __typename?: 'AuthPayload', accessToken: string, user: { __typename?: 'User', id: string, username: string, quotaBytes: number, usedBytes: number, isAdmin: boolean } } };

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


export type NodePreviewQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, name: string, kind: NodeKind, size?: number | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null, largeUrl?: string | null, contentUrl?: string | null, status?: TaskStatus | null, durationSec?: number | null } } };

export type RequestPreviewMutationVariables = Exact<{
  nodeId: Scalars['ID']['input'];
}>;


export type RequestPreviewMutation = { __typename?: 'Mutation', requestPreview: { __typename?: 'PreviewInfo', kind: PreviewKind, status?: TaskStatus | null, contentUrl?: string | null } };

export type NodeQueryVariables = Exact<{
  id: Scalars['ID']['input'];
}>;


export type NodeQuery = { __typename?: 'Query', node: { __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string } };

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


export type MySharesQuery = { __typename?: 'Query', myShares: Array<{ __typename?: 'Share', id: string, token: string, hasPassword: boolean, expiresAt?: string | null, createdAt: string, node: { __typename?: 'Node', id: string, name: string, kind: NodeKind } }> };

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

export type TrashQueryVariables = Exact<{
  cursor?: InputMaybe<Scalars['String']['input']>;
}>;


export type TrashQuery = { __typename?: 'Query', trash: { __typename?: 'NodePage', nextCursor?: string | null, total: number, items: Array<{ __typename?: 'Node', id: string, parentId?: string | null, name: string, kind: NodeKind, size?: number | null, updatedAt: string, deletedAt?: string | null, preview: { __typename?: 'PreviewInfo', kind: PreviewKind, thumbUrl?: string | null } }> } };

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


export const AdminUsersDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminUsers"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminUsers"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}}]}}]}}]} as unknown as DocumentNode<AdminUsersQuery, AdminUsersQueryVariables>;
export const AdminOverviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AdminOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminOverview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"userCount"}},{"kind":"Field","name":{"kind":"Name","value":"totalUsedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"blobCount"}},{"kind":"Field","name":{"kind":"Name","value":"blobBytes"}},{"kind":"Field","name":{"kind":"Name","value":"taskCounts"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"count"}}]}}]}}]}}]} as unknown as DocumentNode<AdminOverviewQuery, AdminOverviewQueryVariables>;
export const AdminCreateUserDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminCreateUser"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminCreateUser"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}},{"kind":"Argument","name":{"kind":"Name","value":"quotaBytes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}}]}}]}}]} as unknown as DocumentNode<AdminCreateUserMutation, AdminCreateUserMutationVariables>;
export const AdminSetQuotaDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminSetQuota"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminSetQuota"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}},{"kind":"Argument","name":{"kind":"Name","value":"quotaBytes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"quotaBytes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}}]}}]}}]} as unknown as DocumentNode<AdminSetQuotaMutation, AdminSetQuotaMutationVariables>;
export const AdminSetDisabledDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminSetDisabled"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"disabled"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminSetDisabled"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}},{"kind":"Argument","name":{"kind":"Name","value":"disabled"},"value":{"kind":"Variable","name":{"kind":"Name","value":"disabled"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"disabled"}}]}}]}}]} as unknown as DocumentNode<AdminSetDisabledMutation, AdminSetDisabledMutationVariables>;
export const AdminResetPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminResetPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"userId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminResetPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"userId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"userId"}}}]}]}}]} as unknown as DocumentNode<AdminResetPasswordMutation, AdminResetPasswordMutationVariables>;
export const AdminRetryFailedTasksDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AdminRetryFailedTasks"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"adminRetryFailedTasks"}}]}}]} as unknown as DocumentNode<AdminRetryFailedTasksMutation, AdminRetryFailedTasksMutationVariables>;
export const AppPasswordsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"AppPasswords"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"appPasswords"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]} as unknown as DocumentNode<AppPasswordsQuery, AppPasswordsQueryVariables>;
export const CreateAppPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateAppPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createAppPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}]}]}}]} as unknown as DocumentNode<CreateAppPasswordMutation, CreateAppPasswordMutationVariables>;
export const RevokeAppPasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeAppPassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeAppPassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeAppPasswordMutation, RevokeAppPasswordMutationVariables>;
export const LoginDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Login"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"login"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<LoginMutation, LoginMutationVariables>;
export const RegisterDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Register"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"username"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"register"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"username"},"value":{"kind":"Variable","name":{"kind":"Name","value":"username"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<RegisterMutation, RegisterMutationVariables>;
export const RefreshDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Refresh"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"refresh"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<RefreshMutation, RefreshMutationVariables>;
export const LogoutDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"Logout"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"logout"}}]}}]} as unknown as DocumentNode<LogoutMutation, LogoutMutationVariables>;
export const MeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Me"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"me"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]} as unknown as DocumentNode<MeQuery, MeQueryVariables>;
export const ChangePasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"ChangePassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"oldPassword"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"newPassword"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"changePassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"oldPassword"},"value":{"kind":"Variable","name":{"kind":"Name","value":"oldPassword"}}},{"kind":"Argument","name":{"kind":"Name","value":"newPassword"},"value":{"kind":"Variable","name":{"kind":"Name","value":"newPassword"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}},{"kind":"Field","name":{"kind":"Name","value":"user"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"username"}},{"kind":"Field","name":{"kind":"Name","value":"quotaBytes"}},{"kind":"Field","name":{"kind":"Name","value":"usedBytes"}},{"kind":"Field","name":{"kind":"Name","value":"isAdmin"}}]}}]}}]}}]} as unknown as DocumentNode<ChangePasswordMutation, ChangePasswordMutationVariables>;
export const McpapiKeysDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MCPAPIKeys"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"mcpAPIKeys"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]} as unknown as DocumentNode<McpapiKeysQuery, McpapiKeysQueryVariables>;
export const CreateMcpapiKeyDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateMCPAPIKey"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"scopes"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createMCPAPIKey"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}},{"kind":"Argument","name":{"kind":"Name","value":"scopes"},"value":{"kind":"Variable","name":{"kind":"Name","value":"scopes"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"credential"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"lastUsedAt"}}]}}]}}]}}]} as unknown as DocumentNode<CreateMcpapiKeyMutation, CreateMcpapiKeyMutationVariables>;
export const RevokeMcpapiKeyDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeMCPAPIKey"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeMCPAPIKey"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeMcpapiKeyMutation, RevokeMcpapiKeyMutationVariables>;
export const OAuthGrantsDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"OAuthGrants"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"oauthGrants"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"clientId"}},{"kind":"Field","name":{"kind":"Name","value":"clientName"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<OAuthGrantsQuery, OAuthGrantsQueryVariables>;
export const RevokeOAuthGrantDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeOAuthGrant"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeOAuthGrant"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeOAuthGrantMutation, RevokeOAuthGrantMutationVariables>;
export const OAuthAuthorizationRequestDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"OAuthAuthorizationRequest"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"OAuthAuthorizationInput"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"oauthAuthorizationRequest"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"clientName"}},{"kind":"Field","name":{"kind":"Name","value":"scopes"}}]}}]}}]} as unknown as DocumentNode<OAuthAuthorizationRequestQuery, OAuthAuthorizationRequestQueryVariables>;
export const DecideOAuthAuthorizationDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DecideOAuthAuthorization"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"input"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"OAuthAuthorizationInput"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"approved"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"decideOAuthAuthorization"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"input"},"value":{"kind":"Variable","name":{"kind":"Name","value":"input"}}},{"kind":"Argument","name":{"kind":"Name","value":"approved"},"value":{"kind":"Variable","name":{"kind":"Name","value":"approved"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"redirectUrl"}}]}}]}}]} as unknown as DocumentNode<DecideOAuthAuthorizationMutation, DecideOAuthAuthorizationMutationVariables>;
export const ChildrenDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Children"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"order"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"NodeOrder"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"desc"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Boolean"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"children"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}},{"kind":"Argument","name":{"kind":"Name","value":"order"},"value":{"kind":"Variable","name":{"kind":"Name","value":"order"}}},{"kind":"Argument","name":{"kind":"Name","value":"desc"},"value":{"kind":"Variable","name":{"kind":"Name","value":"desc"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeBytes"}},{"kind":"Field","name":{"kind":"Name","value":"subtreeCount"}},{"kind":"Field","name":{"kind":"Name","value":"statsStale"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<ChildrenQuery, ChildrenQueryVariables>;
export const NodePreviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodePreview"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}},{"kind":"Field","name":{"kind":"Name","value":"largeUrl"}},{"kind":"Field","name":{"kind":"Name","value":"contentUrl"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"durationSec"}}]}}]}}]}}]} as unknown as DocumentNode<NodePreviewQuery, NodePreviewQueryVariables>;
export const RequestPreviewDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RequestPreview"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"requestPreview"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"status"}},{"kind":"Field","name":{"kind":"Name","value":"contentUrl"}}]}}]}}]} as unknown as DocumentNode<RequestPreviewMutation, RequestPreviewMutationVariables>;
export const NodeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Node"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<NodeQuery, NodeQueryVariables>;
export const CreateFolderDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateFolder"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createFolder"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<CreateFolderMutation, CreateFolderMutationVariables>;
export const RenameNodeDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RenameNode"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"renameNode"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<RenameNodeMutation, RenameNodeMutationVariables>;
export const MoveNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"MoveNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"moveNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}},{"kind":"Argument","name":{"kind":"Name","value":"targetParentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<MoveNodesMutation, MoveNodesMutationVariables>;
export const DeleteNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"DeleteNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"deleteNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}]}]}}]} as unknown as DocumentNode<DeleteNodesMutation, DeleteNodesMutationVariables>;
export const CopyNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CopyNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"copyNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}},{"kind":"Argument","name":{"kind":"Name","value":"targetParentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"targetParentId"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<CopyNodesMutation, CopyNodesMutationVariables>;
export const SearchNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"SearchNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"q"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"searchNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"q"},"value":{"kind":"Variable","name":{"kind":"Name","value":"q"}}},{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<SearchNodesQuery, SearchNodesQueryVariables>;
export const CreateShareDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CreateShare"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"expiresAt"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"Time"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"createShare"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"nodeId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"nodeId"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}},{"kind":"Argument","name":{"kind":"Name","value":"expiresAt"},"value":{"kind":"Variable","name":{"kind":"Name","value":"expiresAt"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}}]}}]}}]} as unknown as DocumentNode<CreateShareMutation, CreateShareMutationVariables>;
export const RevokeShareDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RevokeShare"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"revokeShare"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}]}]}}]} as unknown as DocumentNode<RevokeShareMutation, RevokeShareMutationVariables>;
export const MySharesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"MyShares"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"myShares"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"hasPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"createdAt"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}}]}}]}}]}}]} as unknown as DocumentNode<MySharesQuery, MySharesQueryVariables>;
export const ShareInfoDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ShareInfo"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shareInfo"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"token"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"needPassword"}},{"kind":"Field","name":{"kind":"Name","value":"expired"}}]}}]}}]} as unknown as DocumentNode<ShareInfoQuery, ShareInfoQueryVariables>;
export const VerifySharePasswordDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"VerifySharePassword"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"token"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"password"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"verifySharePassword"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"token"},"value":{"kind":"Variable","name":{"kind":"Name","value":"token"}}},{"kind":"Argument","name":{"kind":"Name","value":"password"},"value":{"kind":"Variable","name":{"kind":"Name","value":"password"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"accessToken"}}]}}]}}]} as unknown as DocumentNode<VerifySharePasswordMutation, VerifySharePasswordMutationVariables>;
export const ShareRootDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"ShareRoot"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"shareRoot"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<ShareRootQuery, ShareRootQueryVariables>;
export const TrashDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"Trash"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"trash"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"cursor"},"value":{"kind":"Variable","name":{"kind":"Name","value":"cursor"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"items"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}},{"kind":"Field","name":{"kind":"Name","value":"deletedAt"}},{"kind":"Field","name":{"kind":"Name","value":"preview"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"thumbUrl"}}]}}]}},{"kind":"Field","name":{"kind":"Name","value":"nextCursor"}},{"kind":"Field","name":{"kind":"Name","value":"total"}}]}}]}}]} as unknown as DocumentNode<TrashQuery, TrashQueryVariables>;
export const RestoreNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"RestoreNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"restoreNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}}]}}]}}]} as unknown as DocumentNode<RestoreNodesMutation, RestoreNodesMutationVariables>;
export const PurgeNodesDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"PurgeNodes"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"ids"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"purgeNodes"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"ids"},"value":{"kind":"Variable","name":{"kind":"Name","value":"ids"}}}]}]}}]} as unknown as DocumentNode<PurgeNodesMutation, PurgeNodesMutationVariables>;
export const PurgeTrashDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"PurgeTrash"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"purgeTrash"}}]}}]} as unknown as DocumentNode<PurgeTrashMutation, PurgeTrashMutationVariables>;
export const InitUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"InitUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}},"type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"name"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sha256"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"String"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"size"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"Int64"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"initUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"parentId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"parentId"}}},{"kind":"Argument","name":{"kind":"Name","value":"name"},"value":{"kind":"Variable","name":{"kind":"Name","value":"name"}}},{"kind":"Argument","name":{"kind":"Name","value":"sha256"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sha256"}}},{"kind":"Argument","name":{"kind":"Name","value":"size"},"value":{"kind":"Variable","name":{"kind":"Name","value":"size"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"instant"}},{"kind":"Field","name":{"kind":"Name","value":"node"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}},{"kind":"Field","name":{"kind":"Name","value":"session"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]}}]}}]} as unknown as DocumentNode<InitUploadMutation, InitUploadMutationVariables>;
export const CompleteUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"CompleteUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}},{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"etags"}},"type":{"kind":"NonNullType","type":{"kind":"ListType","type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"PartEtag"}}}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"completeUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}},{"kind":"Argument","name":{"kind":"Name","value":"etags"},"value":{"kind":"Variable","name":{"kind":"Name","value":"etags"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"parentId"}},{"kind":"Field","name":{"kind":"Name","value":"name"}},{"kind":"Field","name":{"kind":"Name","value":"kind"}},{"kind":"Field","name":{"kind":"Name","value":"size"}},{"kind":"Field","name":{"kind":"Name","value":"updatedAt"}}]}}]}}]} as unknown as DocumentNode<CompleteUploadMutation, CompleteUploadMutationVariables>;
export const AbortUploadDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"mutation","name":{"kind":"Name","value":"AbortUpload"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"abortUpload"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"sessionId"},"value":{"kind":"Variable","name":{"kind":"Name","value":"sessionId"}}}]}]}}]} as unknown as DocumentNode<AbortUploadMutation, AbortUploadMutationVariables>;
export const UploadSessionDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"UploadSession"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"uploadSession"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"partSize"}},{"kind":"Field","name":{"kind":"Name","value":"partUrls"},"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"partNumber"}},{"kind":"Field","name":{"kind":"Name","value":"url"}}]}},{"kind":"Field","name":{"kind":"Name","value":"uploadedParts"}},{"kind":"Field","name":{"kind":"Name","value":"expiresAt"}},{"kind":"Field","name":{"kind":"Name","value":"status"}}]}}]}}]} as unknown as DocumentNode<UploadSessionQuery, UploadSessionQueryVariables>;
export const NodeDownloadUrlDocument = {"kind":"Document","definitions":[{"kind":"OperationDefinition","operation":"query","name":{"kind":"Name","value":"NodeDownloadUrl"},"variableDefinitions":[{"kind":"VariableDefinition","variable":{"kind":"Variable","name":{"kind":"Name","value":"id"}},"type":{"kind":"NonNullType","type":{"kind":"NamedType","name":{"kind":"Name","value":"ID"}}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"node"},"arguments":[{"kind":"Argument","name":{"kind":"Name","value":"id"},"value":{"kind":"Variable","name":{"kind":"Name","value":"id"}}}],"selectionSet":{"kind":"SelectionSet","selections":[{"kind":"Field","name":{"kind":"Name","value":"id"}},{"kind":"Field","name":{"kind":"Name","value":"downloadUrl"}}]}}]}}]} as unknown as DocumentNode<NodeDownloadUrlQuery, NodeDownloadUrlQueryVariables>;