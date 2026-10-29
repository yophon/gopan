export interface Word {
    /** As written, quotes included. */
    raw: string;
    /** With quotes removed. */
    value: string;
}
export type Token = {
    type: "word";
    word: Word;
} | {
    type: "op";
    op: string;
} | {
    type: "redirect";
    op: string;
};
/** Tokens of a command line; substitutions' inner text goes to `nested`. Undefined when quotes or parentheses do not balance. */
export declare function tokenize(command: string, nested: string[]): Token[] | undefined;
