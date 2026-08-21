from pathlib import Path

p=Path('.github/scripts/apply-indexed-direct-topology.py')
src=p.read_text()
# Workflow files are updated through the GitHub connector after the source patch
# lands. A GitHub App token may commit source files but is intentionally blocked
# from pushing workflow-file mutations without the separate workflows permission.
start=src.index('path = ".github/workflows/manual26100-consolidated-regression.yml"')
end=src.index('# Documentation:')
src=src[:start]+src[end:]
exec(compile(src,str(p),'exec'),{'__name__':'__main__','__file__':str(p)})
