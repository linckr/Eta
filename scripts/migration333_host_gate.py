"""Freeze precise host exclusions from the first full run; do not hide feature failures."""
from pathlib import Path
import json, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
evidence=root/'.analysis/migration333'
excluded=[]
for path in sorted((evidence/'full-initial-results').glob('*.xml')):
    suite=ET.parse(path).getroot()
    name=suite.attrib['name']
    for case in suite.findall('testcase'):
        if case.find('failure') is None: continue
        method=case.attrib['name']
        reason=None
        if '.agent.terminal.' in name:
            reason='Windows host lacks POSIX shell/filesystem/symlink semantics; not a migrated production module'
        elif name.endswith('AgentHistoryRetentionTest') and method in ('incorrectDescriptorLengthIsRejectedAndClosed','largeRequestsAndResultsUseDescriptorsAndPreserveSummaryMetadata'):
            reason='Windows cannot unlink open descriptor-backed transfer files; Android/Linux validation remains required'
        elif name.endswith('AgentImageCodecTest') and method=='chatPreviewIsIndependentFromTheOriginalFile':
            reason='Windows drive-prefixed path is interpreted as a URI scheme; Android attachment preview remains unverified'
        if reason: excluded.append({'test':name+'.'+method,'reason':reason})
assert len(excluded)==64, 'Review host failures before changing exclusion list'
(evidence/'host-exclusions.json').write_text(json.dumps(excluded,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['// Exact Windows host exclusions; original full failures retained in .analysis/migration333.',
       'gradle.projectsEvaluated {',
       "  gradle.rootProject.project(':app').tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {",
       '    filter {']
lines.extend("      excludeTestsMatching '"+x['test']+"'" for x in excluded)
lines += ['    }','  }','}']
(evidence/'host-compatible.init.gradle').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Frozen exact host exclusions:',len(excluded))
