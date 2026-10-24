; 安装 / 卸载时的钩子(doc/29 D5):右键菜单、「发送到」与开机启动项由程序自己写(只写当前用户的注册表),
; 卸载时在这里清干净。注册表键名与 src/shell.rs、开机启动插件一致。
; 自动更新时安装包会带 /UPDATE 先跑一遍旧的卸载程序($UpdateMode = 1),那时什么都不删。

!macro NSIS_HOOK_PREINSTALL
!macroend

!macro NSIS_HOOK_POSTINSTALL
!macroend

!macro NSIS_HOOK_PREUNINSTALL
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $UpdateMode <> 1
    DeleteRegKey HKCU "Software\Classes\*\shell\gopan.upload"
    DeleteRegKey HKCU "Software\Classes\*\shell\gopan.send"
    DeleteRegKey HKCU "Software\Classes\Directory\shell\gopan.upload"
    DeleteRegKey HKCU "Software\Classes\Directory\shell\gopan.send"
    Delete "$APPDATA\Microsoft\Windows\SendTo\gopan.lnk"
    Delete "$APPDATA\Microsoft\Windows\SendTo\gopan 传输助手.lnk"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "gopan"
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run" "gopan"
  ${EndIf}
!macroend
