package lyrics

import "testing"

func TestParseSyncedWithRepeatsOffsetAndWordStamps(t *testing.T) {
	got := Parse("\ufeff[ti:晴天]\n[ar:周杰伦]\n[offset:500]\n[00:12.30][01:02.5]故事的小黄花\n[00:20.123]<00:20.12>从<00:20.50>出生\n\n[00:30.00]\nnot a stamp line\n")
	if !got.Synced {
		t.Fatal("should be synced")
	}
	want := []Line{{11.8, "故事的小黄花"}, {19.623, "从出生"}, {29.5, ""}, {62, "故事的小黄花"}}
	if len(got.Lines) != len(want) {
		t.Fatalf("lines %+v", got.Lines)
	}
	for i, w := range want {
		if got.Lines[i] != w {
			t.Errorf("line %d: got %+v want %+v", i, got.Lines[i], w)
		}
	}
}

func TestParsePlainText(t *testing.T) {
	got := Parse("第一行\r\n\r\n第二行\r\n")
	if got.Synced || len(got.Lines) != 2 || got.Lines[1].Text != "第二行" || got.Lines[0].T != -1 {
		t.Fatalf("%+v", got)
	}
}

func TestParseNegativeOffsetAndThreeDigitFraction(t *testing.T) {
	got := Parse("[offset:-250]\n[00:00.100]开头\n[02:03.456]结尾")
	if got.Lines[0].T != 0.35 || got.Lines[1].T != 123.706 {
		t.Fatalf("%+v", got.Lines)
	}
}
