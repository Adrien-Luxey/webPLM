
(function () {
	'use strict';
	
	angular
		.module('PLMApp')
		.factory('Lander', Lander);
	
	function Lander() {
		
		var Lander = function (lander) {
			this.x = lander.x;
			this.y = lander.y;
			this.thrust = lander.thrust;
			this.angle = lander.angle;
			this.desiredThrust = lander.desiredThrust;
			this.desiredAngle = lander.desiredAngle;
			this.fuel = lander.fuel;
			this.speedX = lander.speedX;
			this.speedY = lander.speedY;
		}

		return Lander;
	}
}());
