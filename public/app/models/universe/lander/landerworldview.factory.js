// TODO: text indications on top left
(function () {
  'use strict';

  angular
    .module('PLMApp')
    .factory('LanderWorldView', LanderWorldView);

  LanderWorldView.$inject = [];

  function LanderWorldView() {
    var ctx, canvasWidth, canvasHeight, turtleImg;

    landerImg = new Image();
    landerImg.src = '/assets/images/world_lander.png';
    
    var service = {
      draw: draw
    };

    function initUtils(canvas, landerWorld) {
      ctx = canvas.getContext('2d');
      canvasWidth = canvas.width;
      canvasHeight = canvas.height;
    }

    function draw(canvas, landerWorld) {
      var landerID, lander;

      initUtils(canvas, landerWorld);
      
			var groundLength = landerWorld.ground.length;
			var prevPoint = landerWorld.ground[0];
			for( var i = 1; i < groundLength; i++ ){
				var point = landerWorld.ground[i];

				ctx.beginPath();
				ctx.lineWidth = 1;
				ctx.strokeStyle = "#000000";
				ctx.moveTo(prevPoint.x, prevPoint.y);
				ctx.lineTo(point.x, point.y);
				ctx.stroke();
				ctx.closePath();
				
				prevPoint = point;
			}

      for (landerID in landerWorld.entities) {
        if (landerWorld.entities.hasOwnProperty(landerID)) {
        	drawLander(landerWorld.entities[landerID]);
        }
      }
    }
    
    function drawLander(lander) {
      ctx.save();
      ctx.translate(lander.x, lander.y);

      // rotate around this point
      ctx.rotate(degreeToRadian(lander.angle));

      // then draw the image back and up
      ctx.drawImage(landerImg, -16, -16);

			if( lander.thrust != 0 ) {
				var radius = 3;
				ctx.scale(1, lander.thrust + 1);
				ctx.beginPath();
				ctx.arc(0, radius, radius, 0, 2 * Math.PI, false);

				ctx.strokeStyle = "#000000";
				ctx.stroke();
			}

      ctx.restore();
		}

    function degreeToRadian(angle) {
      return angle * Math.PI / 180;
    }
    
		return service;
	}
}());
