package com.shsh.pleos.playground.platform

/** Identical official IFrame page for the app and the debug native rendering comparison. */
object YouTubePlayerPage {
    const val BASE_URL = "https://com.shsh.pleos.playground/"
    fun html(id: String, playOnReady: Boolean, logPrefix: String = "SHSH_PLAYER"): String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        require(Regex("[A-Z_]{1,40}").matches(logPrefix))
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;}
              #playerHost{position:fixed;inset:0;width:100vw;height:100vh;overflow:hidden;}
              #player{display:block;width:100%;height:100%;border:0;}
            </style></head><body><div id="playerHost"><div id="player"></div></div><script>
            var player,playerReady=false,lastSize='',playRequested=$playOnReady;
            function viewportSize(){
              return {width:Math.round(window.innerWidth||document.documentElement.clientWidth),
                height:Math.round(window.innerHeight||document.documentElement.clientHeight)};
            }
            function reportSize(){
              var frame=player&&player.getIframe?player.getIframe():document.getElementById('player');
              var rect=frame?frame.getBoundingClientRect():null;
              console.log('$logPrefix:Layout='+window.innerWidth+'x'+window.innerHeight+
                ';iframe='+(rect?Math.round(rect.width)+'x'+Math.round(rect.height):'missing'));
            }
            function fitPlayer(target){
              var size=viewportSize();
              if(!target||size.width<=0||size.height<=0){return false;}
              target.setSize(size.width,size.height);
              var frame=target.getIframe();
              frame.style.setProperty('width',size.width+'px','important');
              frame.style.setProperty('height',size.height+'px','important');
              frame.style.display='block';frame.style.border='0';
              var nextSize=size.width+'x'+size.height;
              if(nextSize!==lastSize){lastSize=nextSize;requestAnimationFrame(reportSize);}
              return true;
            }
            function requestPlayback(){
              playRequested=true;
              if(playerReady&&fitPlayer(player)){
                console.log('$logPrefix:PlayRequested');
                player.playVideo();
              }
            }
            function createWhenSized(){
              var size=viewportSize();
              if(size.width<=0||size.height<=0){setTimeout(createWhenSized,50);return;}
              player=new YT.Player('player',{
                width:size.width,height:size.height,videoId:'$id',
                playerVars:{playsinline:1,autoplay:0,origin:'https://com.shsh.pleos.playground'},
                events:{onReady:function(e){
                    playerReady=true;fitPlayer(e.target);
                    requestAnimationFrame(function(){fitPlayer(e.target);reportSize();if(playRequested){requestPlayback();}});
                    console.log('$logPrefix:Ready');
                  },
                  onAutoplayBlocked:function(){console.log('$logPrefix:AutoplayBlocked');},
                  onStateChange:function(e){console.log('$logPrefix:State='+e.data);},
                  onError:function(e){console.log('$logPrefix:Error='+e.data);}}
              });
            }
            function onYouTubeIframeAPIReady(){createWhenSized();}
            window.addEventListener('resize',function(){if(playerReady){fitPlayer(player);}});
            if(typeof ResizeObserver!=='undefined'){
              new ResizeObserver(function(){if(playerReady){fitPlayer(player);}}).observe(document.getElementById('playerHost'));
            }
            </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
        """.trimIndent()
    }
}
