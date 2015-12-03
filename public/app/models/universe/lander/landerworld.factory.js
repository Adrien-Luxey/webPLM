(function () {
	'use strict';

	angular
		.module('PLMApp')
		.factory('LanderWorld', LanderWorld);

	//LanderWorld.$inject = ['Lander','Ground',
	//											'setDesiredAngle', 'setDesiredThrust'];
	LanderWorld.$inject = ['Lander'];

	//function LanderWorldWrite(Lander, Ground,
	//													setDesiredAngle, setDesiredThrust) {
	function LanderWorld(Lander) {

		var LanderWorld = function (world) {
			var landerID, lander;
	
			this.type = world.type;
			this.width = world.width;
			this.height = world.height;
			this.operations = [];
			this.currentState = -1;
			this.steps = [];

			this.ground = [];
			this.addGround(world.ground);

			this.position = world.position;
			this.speed = world.speed;
			this.angle = world.angle;
			this.thrust = world.thrust;
			this.fuel = world.fuel;
			this.desiredAngle = world.desiredAngle;
			this.desiredThrust = world.desiredThrust;

	      this.entities = {};
			for (landerID in world.entities) {
				if (world.entities.hasOwnProperty(turtleID)) {
					lander = world.entities[landerID];
					this.entities[landerID] = new Lander(lander);
				}
			}
		
		};

		LanderWorld.prototype.getEntity = function (entityID) {
			return this.entities[entityID];
		};
    
		LanderWorld.prototype.clone = function () {
			return new LanderWorld(this);
		};

		LanderWorld.prototype.addOperations = function (operations) {
			var i, operation, generatedOperation, step = [];
			for (i = 0; i < operations.length; i += 1) {
				operation = operations[i];				generatedOperation = this.generateOperation(operation);
				generatedOperation = this.generateOperation(operation);
				step.push(generatedOperation);
			}
			this.operations.push(step);
		};

		LanderWorld.prototype.setState = function (state) {
			var i, j, step;
			if (state < this.operations.length && state >= -1) {
				if (this.currentState < state) {
					for (i = this.currentState + 1; i <= state; i += 1) {
						step = this.operations[i];
						for (j = 0; j < step.length; j += 1) {
							step[j].apply(this);
						}
					}
				} else {
					for (i = this.currentState; i > state; i -= 1) {
						step = this.operations[i];
						for (j = 0; j < step.length; j += 1) {
							step[j].reverse(this);
						}
					}
				}
				this.currentState = state;
			}
		};

		LanderWorld.prototype.generatedOperation = function (operation) {
			switch (operation.type) {
      case 'setDesiredAngle':
        return new setDesiredAngle(operation);
      case 'setDesiredThrust':
        return new setDesiredThrust(operation);
      default:
        console.log('Operation not supported yet: ', operation);
			}
		};


		return LanderWorld;
	}
}());
