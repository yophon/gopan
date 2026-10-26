/**
 * 桌面端「用播放器播放」(doc/31 三):外壳找电脑上的播放器(PotPlayer、VLC、mpv、MPC,没有就用系统自带的
 * Windows Media Player),把 12 小时有效的地址和同名外挂字幕交过去,边下边放。返回播放器的名字。
 */
import { invokeDesktop } from './bridge'

export function playExternal(nodeId: string): Promise<string> {
  return invokeDesktop<string>('desktop_play_external', { id: nodeId })
}

/** 视频、音乐才有这一项 */
export function canPlayExternal(previewKind: string | null | undefined): boolean {
  return previewKind === 'VIDEO' || previewKind === 'AUDIO'
}
