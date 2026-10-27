"""Writes demo-epg.xml: programmes every 30 min for the demo channels, from 6 h ago to 24 h ahead."""
import datetime, sys
now = datetime.datetime.now(datetime.timezone.utc).replace(minute=0, second=0, microsecond=0)
chans = {"mux.test": "Mux Test", "apple.bipbop": "Apple BipBop", "akamai.live": "Akamai Live"}
titles = ["Journal", "Documentaire nature", "Film du soir", "Sport : résumé", "Magazine tech", "Dessins animés", "Météo"]
out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="lumen-demo">']
for cid, name in chans.items():
    out.append(f'<channel id="{cid}"><display-name>{name}</display-name></channel>')
for cid in chans:
    t = now - datetime.timedelta(hours=6)
    i = 0
    while t < now + datetime.timedelta(hours=24):
        e = t + datetime.timedelta(minutes=30)
        f = lambda d: d.strftime("%Y%m%d%H%M%S +0000")
        out.append(f'<programme start="{f(t)}" stop="{f(e)}" channel="{cid}"><title>{titles[i % len(titles)]}</title>'
                   f'<desc>Programme de démonstration n° {i} pour tester le guide de Lumen TV.</desc></programme>')
        t, i = e, i + 1
out.append('</tv>')
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(out))
