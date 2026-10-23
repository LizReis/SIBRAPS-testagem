import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AgendarGrupoTerapeutico } from './agendar-grupo-terapeutico';

describe('AgendarGrupoTerapeutico', () => {
  let component: AgendarGrupoTerapeutico;
  let fixture: ComponentFixture<AgendarGrupoTerapeutico>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AgendarGrupoTerapeutico],
      providers: [provideHttpClient(), provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(AgendarGrupoTerapeutico);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
