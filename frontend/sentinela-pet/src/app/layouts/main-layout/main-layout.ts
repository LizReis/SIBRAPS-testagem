import { Component, HostListener, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Header } from '../../shared/header/header';
import { Sidebar } from '../../shared/sidebar/sidebar';

@Component({ 
    selector: 'app-main-layout', 
    imports: [Header, RouterOutlet, Sidebar], 
    templateUrl: './main-layout.html', 
    styleUrl: './main-layout.css' })
export class MainLayout implements OnInit {
  isSidebarOpen = signal(true);

  isMobile = signal(false);
  ngOnInit() { this.verificarTela(); }
  @HostListener('window:resize') verificarTela() { 
      const mobile = window.innerWidth <= 768; 
      this.isMobile.set(mobile); 
      this.isSidebarOpen.set(!mobile); 
    }
  toggleSidebar() { this.isSidebarOpen.set(!this.isSidebarOpen()); }
}
