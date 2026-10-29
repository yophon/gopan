/** A PowerShell command taken apart; the shape bash commands have (permission-rules.ts ParsedCommand). */
export interface ParsedPowerShellCommand {
    /** False when the command uses syntax the tokenizer does not take apart, or a quote is left open. */
    ok: boolean;
    raw: string;
    /** Every command, its alias replaced by the cmdlet name, words joined by one space. */
    segments: string[];
    /** Redirection targets (checked like edits). */
    writes: string[];
    /** Always empty: kept for the bash shape. */
    reads: string[];
}
/** The cmdlet an alias stands for (case-insensitive); other names come back as written. */
export declare function canonicalPowerShellCommand(name: string): string;
/** Take a PowerShell command apart (see the module comment). */
export declare function parsePowerShellCommand(command: string): ParsedPowerShellCommand;
/** A PowerShell rule pattern against one command: bash's wildcard rules, aliases resolved, case ignored. */
export declare function canonicalPowerShellPattern(pattern: string): string;
